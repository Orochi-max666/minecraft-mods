package com.orochi.spotifybar.spotify;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Login con Spotify mediante Authorization Code + PKCE.
 *
 * <p>No hace falta "client secret": el usuario crea su propia app en el dashboard de Spotify, pega el
 * Client ID, y este objeto abre el navegador y recibe el codigo en un servidor local temporal
 * ({@code http://127.0.0.1:PUERTO/callback}). El refresh token se guarda en disco para no repetir el login.
 */
public final class SpotifyAuth {
    public static final String SCOPES = String.join(" ",
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "playlist-read-private",
            "playlist-read-collaborative");

    public enum LoginState { IDLE, WAITING, FAILED }

    /** Contenido del fichero de tokens. */
    private static final class TokenData {
        String clientId;
        String refreshToken;
        String accessToken;
        long expiresAt;
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Gson GSON = new Gson();

    private final Path tokenFile;
    private final HttpClient http;
    private final String accountsBase;

    private TokenData tokens; // protegido por "this"
    private HttpServer server; // protegido por "this"
    private volatile LoginState loginState = LoginState.IDLE;
    private volatile String loginError;
    private volatile String pendingUrl;

    public SpotifyAuth(Path tokenFile, HttpClient http, String accountsBase) {
        this.tokenFile = tokenFile;
        this.http = http;
        this.accountsBase = accountsBase;
        this.tokens = load();
    }

    // ------------------------------------------------------------------ estado

    public synchronized boolean isLoggedIn() {
        return tokens != null && tokens.refreshToken != null;
    }

    public LoginState loginState() {
        return loginState;
    }

    public String loginError() {
        return loginError;
    }

    /** URL de autorizacion del login en curso (para copiarla si el navegador no se abre solo). */
    public String pendingUrl() {
        return pendingUrl;
    }

    public synchronized void logout() {
        cancelLogin();
        tokens = null;
        try {
            Files.deleteIfExists(tokenFile);
        } catch (IOException ignored) {
            // si no se puede borrar, al menos ya no se usa en memoria
        }
    }

    // ------------------------------------------------------------------ tokens

    /** Devuelve un access token valido, renovandolo si esta a punto de caducar. */
    public synchronized String accessToken(boolean forceRefresh) {
        if (tokens == null || tokens.refreshToken == null) {
            throw new SpotifyException(SpotifyException.Kind.NOT_LOGGED_IN, 0, "No hay sesion de Spotify");
        }
        boolean fresh = tokens.accessToken != null && System.currentTimeMillis() < tokens.expiresAt - 30_000L;
        if (forceRefresh || !fresh) {
            refresh();
        }
        return tokens.accessToken;
    }

    private void refresh() {
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", tokens.refreshToken);
        form.put("client_id", tokens.clientId);
        HttpResponse<String> resp = postForm(form);
        JsonObject body = parseObject(resp.body());
        if (resp.statusCode() != 200) {
            String err = Json.str(body, "error", "");
            if (resp.statusCode() == 400 || resp.statusCode() == 401) {
                if ("invalid_grant".equals(err) || "invalid_client".equals(err)) {
                    logout(); // el refresh token ya no vale: hay que volver a conectar
                }
                throw new SpotifyException(SpotifyException.Kind.AUTH_FAILED, resp.statusCode(),
                        Json.str(body, "error_description", err));
            }
            throw new SpotifyException(SpotifyException.Kind.OTHER, resp.statusCode(), "Token: HTTP " + resp.statusCode());
        }
        applyTokenResponse(body);
    }

    private void applyTokenResponse(JsonObject body) {
        tokens.accessToken = Json.str(body, "access_token", null);
        tokens.expiresAt = System.currentTimeMillis() + Json.lng(body, "expires_in", 3600) * 1000L;
        String rotated = Json.str(body, "refresh_token", null);
        if (rotated != null) {
            tokens.refreshToken = rotated;
        }
        save();
    }

    // ------------------------------------------------------------------ login

    /**
     * Inicia el login. Abre el navegador con {@code openBrowser} y devuelve un future que se completa
     * cuando el usuario autoriza (o falla / caduca a los 3 minutos).
     */
    public synchronized CompletableFuture<Void> beginLogin(String clientId, int port, Consumer<String> openBrowser) {
        cancelLogin();
        loginError = null;

        String verifier = randomUrlSafe(64);
        String challenge = sha256Base64Url(verifier);
        String expectedState = randomUrlSafe(16);
        String redirect = redirectUri(port);

        CompletableFuture<Void> result = new CompletableFuture<>();
        HttpServer srv;
        try {
            srv = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), port), 0);
        } catch (IOException e) {
            loginState = LoginState.FAILED;
            loginError = "No se pudo abrir el puerto " + port + " (" + e.getMessage() + ")";
            result.completeExceptionally(new SpotifyException(SpotifyException.Kind.AUTH_FAILED, 0, loginError));
            return result;
        }
        server = srv;

        srv.createContext("/callback", ex -> handleCallback(ex, clientId, redirect, verifier, expectedState, result));
        srv.start();

        pendingUrl = "https://accounts.spotify.com/authorize"
                + "?client_id=" + enc(clientId)
                + "&response_type=code"
                + "&redirect_uri=" + enc(redirect)
                + "&code_challenge_method=S256"
                + "&code_challenge=" + enc(challenge)
                + "&state=" + enc(expectedState)
                + "&scope=" + enc(SCOPES);
        loginState = LoginState.WAITING;

        result.orTimeout(3, TimeUnit.MINUTES).whenComplete((v, err) -> {
            synchronized (SpotifyAuth.this) {
                if (server == srv) {
                    stopServerAsync(srv);
                    server = null;
                }
            }
            if (err != null) {
                loginState = LoginState.FAILED;
                loginError = err.getCause() != null ? String.valueOf(err.getCause().getMessage()) : String.valueOf(err.getMessage());
            } else {
                loginState = LoginState.IDLE;
            }
            pendingUrl = null;
        });

        try {
            openBrowser.accept(pendingUrl);
        } catch (RuntimeException e) {
            // el usuario aun puede copiar el enlace manualmente
        }
        return result;
    }

    public synchronized void cancelLogin() {
        if (server != null) {
            stopServerAsync(server);
            server = null;
        }
        if (loginState == LoginState.WAITING) {
            loginState = LoginState.IDLE;
        }
        pendingUrl = null;
    }

    private void handleCallback(HttpExchange ex, String clientId, String redirect, String verifier,
                                String expectedState, CompletableFuture<Void> result) throws IOException {
        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String page;
        try {
            if (q.containsKey("error")) {
                throw new SpotifyException(SpotifyException.Kind.AUTH_FAILED, 0, "Spotify: " + q.get("error"));
            }
            if (!expectedState.equals(q.get("state")) || q.get("code") == null) {
                throw new SpotifyException(SpotifyException.Kind.AUTH_FAILED, 0, "Respuesta de login no valida");
            }
            Map<String, String> form = new HashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", q.get("code"));
            form.put("redirect_uri", redirect);
            form.put("client_id", clientId);
            form.put("code_verifier", verifier);
            HttpResponse<String> resp = postForm(form);
            JsonObject body = parseObject(resp.body());
            if (resp.statusCode() != 200) {
                throw new SpotifyException(SpotifyException.Kind.AUTH_FAILED, resp.statusCode(),
                        Json.str(body, "error_description", "HTTP " + resp.statusCode()));
            }
            synchronized (this) {
                tokens = new TokenData();
                tokens.clientId = clientId;
                tokens.refreshToken = Json.str(body, "refresh_token", null);
                applyTokenResponse(body);
            }
            page = htmlPage("Spotify conectado", "Ya puedes volver a Minecraft y cerrar esta pestana.");
            respond(ex, 200, page);
            result.complete(null);
        } catch (SpotifyException e) {
            respond(ex, 400, htmlPage("No se pudo conectar", escapeHtml(String.valueOf(e.getMessage()))));
            result.completeExceptionally(e);
        } catch (RuntimeException e) {
            respond(ex, 500, htmlPage("Error", escapeHtml(String.valueOf(e.getMessage()))));
            result.completeExceptionally(e);
        }
    }

    private static void respond(HttpExchange ex, int code, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String htmlPage(String title, String text) {
        return "<!doctype html><meta charset=utf-8><title>" + title + "</title>"
                + "<body style=\"font-family:sans-serif;background:#121212;color:#fff;text-align:center;padding-top:15vh\">"
                + "<h1 style=\"color:#1DB954\">" + title + "</h1><p>" + text + "</p></body>";
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static void stopServerAsync(HttpServer srv) {
        // stop() desde dentro de un handler puede bloquear; se hace en un hilo aparte
        Thread t = new Thread(() -> srv.stop(0), "SpotifyBar-login-stop");
        t.setDaemon(true);
        t.start();
    }

    public static String redirectUri(int port) {
        return "http://127.0.0.1:" + port + "/callback";
    }

    // ------------------------------------------------------------------ HTTP / disco

    private HttpResponse<String> postForm(Map<String, String> form) {
        StringBuilder sb = new StringBuilder();
        form.forEach((k, v) -> {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(k)).append('=').append(enc(v));
        });
        HttpRequest req = HttpRequest.newBuilder(URI.create(accountsBase + "/api/token"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString()))
                .build();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new SpotifyException(SpotifyException.Kind.NETWORK, 0, "Sin conexion con Spotify", 0L, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SpotifyException(SpotifyException.Kind.NETWORK, 0, "Interrumpido", 0L, e);
        }
    }

    private TokenData load() {
        try {
            if (Files.isRegularFile(tokenFile)) {
                TokenData d = GSON.fromJson(Files.readString(tokenFile), TokenData.class);
                if (d != null && d.refreshToken != null && d.clientId != null) {
                    return d;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // fichero corrupto: se trata como "sin sesion"
        }
        return null;
    }

    private void save() {
        try {
            Files.createDirectories(tokenFile.getParent());
            Path tmp = tokenFile.resolveSibling(tokenFile.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(tokens));
            try {
                Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException | IOException ignored) {
                // Windows: sin permisos POSIX
            }
            Files.move(tmp, tokenFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // la sesion sigue valida en memoria; solo se pierde la persistencia
        }
    }

    // ------------------------------------------------------------------ utilidades

    private static JsonObject parseObject(String body) {
        try {
            var e = JsonParser.parseString(body == null ? "" : body);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String randomUrlSafe(int bytes) {
        byte[] b = new byte[bytes];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    static String sha256Base64Url(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
