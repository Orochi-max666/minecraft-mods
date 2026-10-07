package com.orochi.spotifybar.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.orochi.spotifybar.spotify.SpotifyModels.Device;
import com.orochi.spotifybar.spotify.SpotifyModels.Item;
import com.orochi.spotifybar.spotify.SpotifyModels.ItemKind;
import com.orochi.spotifybar.spotify.SpotifyModels.Playback;
import com.orochi.spotifybar.spotify.SpotifyModels.Track;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/** Cliente minimo y bloqueante de la Web API de Spotify (se usa siempre desde hilos de fondo). */
public final class SpotifyApi {
    private final HttpClient http;
    private final SpotifyAuth auth;
    private final String base;

    public SpotifyApi(HttpClient http, SpotifyAuth auth, String baseUrl) {
        this.http = http;
        this.auth = auth;
        this.base = baseUrl;
    }

    // ------------------------------------------------------------------ lectura

    public Playback getPlayback() {
        HttpResponse<String> r = send("GET", "/v1/me/player", params("additional_types", "track,episode"), null);
        if (r.statusCode() == 204 || r.body() == null || r.body().isBlank()) {
            return Playback.NONE; // no hay dispositivo activo
        }
        return parsePlayback(checked(r));
    }

    public List<Device> devices() {
        JsonObject o = checked(send("GET", "/v1/me/player/devices", null, null));
        List<Device> out = new ArrayList<>();
        for (JsonElement e : Json.arr(o, "devices")) {
            if (e.isJsonObject()) out.add(parseDevice(e.getAsJsonObject()));
        }
        return out;
    }

    public List<Item> playlists() {
        JsonObject o = checked(send("GET", "/v1/me/playlists", params("limit", "50"), null));
        List<Item> out = new ArrayList<>();
        for (JsonElement e : Json.arr(o, "items")) {
            if (e.isJsonObject()) out.add(parsePlaylist(e.getAsJsonObject()));
        }
        return out;
    }

    /** Busca canciones y playlists. Primero las canciones, luego las playlists. */
    public List<Item> search(String query) {
        Map<String, String> q = params("q", query);
        q.put("type", "track,playlist");
        q.put("limit", "10");
        JsonObject o = checked(send("GET", "/v1/search", q, null));
        List<Item> out = new ArrayList<>();
        for (JsonElement e : Json.arr(Json.obj(o, "tracks"), "items")) {
            if (e.isJsonObject()) {
                Track t = parseTrack(e.getAsJsonObject());
                out.add(new Item(ItemKind.TRACK, t.uri(), t.name(), t.artists()));
            }
        }
        for (JsonElement e : Json.arr(Json.obj(o, "playlists"), "items")) {
            if (e.isJsonObject()) out.add(parsePlaylist(e.getAsJsonObject()));
        }
        return out;
    }

    // ------------------------------------------------------------------ control

    /** Reanuda ({@code body == null}) o reproduce un contexto / lista de canciones. */
    public void play(JsonObject body, String deviceId) {
        checked(send("PUT", "/v1/me/player/play", params("device_id", deviceId), body == null ? null : body.toString()));
    }

    public void pause() {
        checked(send("PUT", "/v1/me/player/pause", null, null));
    }

    public void next(String deviceId) {
        checked(send("POST", "/v1/me/player/next", params("device_id", deviceId), null));
    }

    public void previous(String deviceId) {
        checked(send("POST", "/v1/me/player/previous", params("device_id", deviceId), null));
    }

    public void seek(long positionMs) {
        checked(send("PUT", "/v1/me/player/seek", params("position_ms", Long.toString(Math.max(0, positionMs))), null));
    }

    public void setVolume(int percent) {
        int v = Math.max(0, Math.min(100, percent));
        checked(send("PUT", "/v1/me/player/volume", params("volume_percent", Integer.toString(v)), null));
    }

    public void setShuffle(boolean on) {
        checked(send("PUT", "/v1/me/player/shuffle", params("state", Boolean.toString(on)), null));
    }

    public void setRepeat(String mode) {
        checked(send("PUT", "/v1/me/player/repeat", params("state", mode), null));
    }

    // ------------------------------------------------------------------ HTTP

    private HttpResponse<String> send(String method, String path, Map<String, String> query, String jsonBody) {
        for (int attempt = 0; ; attempt++) {
            String token = auth.accessToken(attempt > 0);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path + queryString(query)))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + token)
                    .header("Accept", "application/json");
            if (jsonBody != null) b.header("Content-Type", "application/json");
            b.method(method, jsonBody == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(jsonBody));
            HttpResponse<String> resp;
            try {
                resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new SpotifyException(SpotifyException.Kind.NETWORK, 0, "Sin conexion con Spotify", 0L, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SpotifyException(SpotifyException.Kind.NETWORK, 0, "Interrumpido", 0L, e);
            }
            if (resp.statusCode() == 401 && attempt == 0) {
                continue; // token caducado: se renueva y se reintenta una vez
            }
            return resp;
        }
    }

    /** Devuelve el JSON de una respuesta correcta (vacio si no hay cuerpo) o lanza la excepcion adecuada. */
    private static JsonObject checked(HttpResponse<String> r) {
        int code = r.statusCode();
        if (code >= 200 && code < 300) {
            return parseObject(r.body());
        }
        JsonObject body = parseObject(r.body());
        JsonObject err = Json.obj(body, "error");
        String message = err != null ? Json.str(err, "message", "HTTP " + code) : "HTTP " + code;
        String reason = err != null ? Json.str(err, "reason", "") : "";

        SpotifyException.Kind kind = SpotifyException.Kind.OTHER;
        long retryMs = 0L;
        if (code == 401) {
            kind = SpotifyException.Kind.AUTH_FAILED;
        } else if (code == 429) {
            kind = SpotifyException.Kind.RATE_LIMITED;
            retryMs = r.headers().firstValue("Retry-After").map(SpotifyApi::parseSecondsToMs).orElse(5_000L);
        } else if ("NO_ACTIVE_DEVICE".equals(reason)) {
            kind = SpotifyException.Kind.NO_DEVICE;
        } else if ("PREMIUM_REQUIRED".equals(reason)) {
            kind = SpotifyException.Kind.PREMIUM_REQUIRED;
        }
        throw new SpotifyException(kind, code, message, retryMs, null);
    }

    private static long parseSecondsToMs(String s) {
        try {
            return Long.parseLong(s.trim()) * 1000L;
        } catch (NumberFormatException e) {
            return 5_000L;
        }
    }

    private static JsonObject parseObject(String body) {
        if (body == null || body.isBlank()) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(body);
            return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return new JsonObject();
        }
    }

    private static Map<String, String> params(String k, String v) {
        Map<String, String> m = new LinkedHashMap<>();
        if (v != null) m.put(k, v);
        return m;
    }

    private static String queryString(Map<String, String> q) {
        if (q == null || q.isEmpty()) return "";
        StringJoiner j = new StringJoiner("&", "?", "");
        q.forEach((k, v) -> j.add(URLEncoder.encode(k, StandardCharsets.UTF_8) + "=" + URLEncoder.encode(v, StandardCharsets.UTF_8)));
        return j.toString();
    }

    // ------------------------------------------------------------------ parseo

    static Playback parsePlayback(JsonObject o) {
        JsonObject item = Json.obj(o, "item");
        JsonObject ctx = Json.obj(o, "context");
        return new Playback(
                item == null ? null : parseTrack(item),
                Json.bool(o, "is_playing", false),
                Json.lng(o, "progress_ms", 0L),
                Json.bool(o, "shuffle_state", false),
                Json.str(o, "repeat_state", "off"),
                Json.obj(o, "device") == null ? null : parseDevice(Json.obj(o, "device")),
                ctx == null ? null : Json.str(ctx, "uri", null));
    }

    static Track parseTrack(JsonObject t) {
        // Las canciones traen "artists"+"album"; los episodios de podcast traen "show" y sus propias imagenes.
        StringJoiner artists = new StringJoiner(", ");
        for (JsonElement a : Json.arr(t, "artists")) {
            if (a.isJsonObject()) {
                String name = Json.str(a.getAsJsonObject(), "name", null);
                if (name != null) artists.add(name);
            }
        }
        JsonObject album = Json.obj(t, "album");
        JsonObject show = Json.obj(t, "show");
        String albumName = album != null ? Json.str(album, "name", "") : show != null ? Json.str(show, "name", "") : "";
        String artistText = artists.length() > 0 ? artists.toString() : show != null ? Json.str(show, "publisher", "") : "";
        JsonArray images = album != null ? Json.arr(album, "images") : Json.arr(t, "images");
        return new Track(Json.str(t, "uri", ""), Json.str(t, "name", "?"), artistText, albumName,
                pickImage(images), Json.lng(t, "duration_ms", 0L));
    }

    static Device parseDevice(JsonObject d) {
        return new Device(Json.str(d, "id", null), Json.str(d, "name", "?"), Json.str(d, "type", ""),
                Json.bool(d, "is_active", false), (int) Json.lng(d, "volume_percent", 0L),
                // volume_percent es null en dispositivos que no permiten cambiar el volumen (p. ej. iOS)
                d.has("volume_percent") && !d.get("volume_percent").isJsonNull());
    }

    private static Item parsePlaylist(JsonObject p) {
        JsonObject owner = Json.obj(p, "owner");
        return new Item(ItemKind.PLAYLIST, Json.str(p, "uri", ""), Json.str(p, "name", "?"),
                owner == null ? "" : Json.str(owner, "display_name", ""));
    }

    /** Elige la imagen mas pequena que aun tenga al menos 64 px (Spotify las ordena de mayor a menor). */
    private static String pickImage(JsonArray images) {
        String best = null;
        long bestWidth = Long.MAX_VALUE;
        String any = null;
        for (JsonElement e : images) {
            if (!e.isJsonObject()) continue;
            JsonObject img = e.getAsJsonObject();
            String url = Json.str(img, "url", null);
            if (url == null) continue;
            if (any == null) any = url;
            long w = Json.lng(img, "width", 0L);
            if (w >= 64 && w < bestWidth) {
                best = url;
                bestWidth = w;
            }
        }
        return best != null ? best : any;
    }
}
