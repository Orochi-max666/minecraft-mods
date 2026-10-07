package com.orochi.spotifybar.spotify;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.orochi.spotifybar.spotify.SpotifyModels.Device;
import com.orochi.spotifybar.spotify.SpotifyModels.Item;
import com.orochi.spotifybar.spotify.SpotifyModels.Playback;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Fachada que usa el resto del mod. Todo lo que toca la red va a hilos de fondo; los metodos de estado
 * ({@link #playback()}, {@link #progressMs()}...) son seguros de llamar desde el hilo de render.
 */
public final class SpotifyService {
    public static final String ACCOUNTS_BASE = "https://accounts.spotify.com";
    public static final String API_BASE = "https://api.spotify.com";

    private record Snapshot(Playback playback, long takenAtMs) {
        static final Snapshot EMPTY = new Snapshot(Playback.NONE, 0L);
    }

    private final SpotifyAuth auth;
    private final SpotifyApi api;
    private final ScheduledExecutorService exec;
    private final Object pollLock = new Object();

    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private volatile SpotifyException lastPollError;
    private volatile long backoffUntilMs;
    private ScheduledFuture<?> pollTask;

    public SpotifyService(Path tokenFile) {
        this(tokenFile, ACCOUNTS_BASE, API_BASE);
    }

    public SpotifyService(Path tokenFile, String accountsBase, String apiBase) {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.auth = new SpotifyAuth(tokenFile, http, accountsBase);
        this.api = new SpotifyApi(http, auth, apiBase);
        AtomicInteger n = new AtomicInteger();
        this.exec = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "SpotifyBar-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    // ------------------------------------------------------------------ sesion

    public boolean isLoggedIn() {
        return auth.isLoggedIn();
    }

    public SpotifyAuth.LoginState loginState() {
        return auth.loginState();
    }

    public String loginError() {
        return auth.loginError();
    }

    public String pendingLoginUrl() {
        return auth.pendingUrl();
    }

    public CompletableFuture<Void> beginLogin(String clientId, int port, Consumer<String> openBrowser) {
        return auth.beginLogin(clientId, port, openBrowser).thenRun(this::refreshSoon);
    }

    public void cancelLogin() {
        auth.cancelLogin();
    }

    public void logout() {
        auth.logout();
        snapshot = Snapshot.EMPTY;
    }

    // ------------------------------------------------------------------ estado

    public Playback playback() {
        return snapshot.playback();
    }

    /** Progreso interpolado entre sondeos para que la barra avance suave. */
    public long progressMs() {
        Snapshot s = snapshot;
        long p = s.playback().progressMs();
        if (s.playback().playing()) {
            p += System.currentTimeMillis() - s.takenAtMs();
        }
        if (s.playback().track() != null) {
            p = Math.min(p, s.playback().track().durationMs());
        }
        return Math.max(0L, p);
    }

    /** Ultimo error del sondeo periodico (o null si el ultimo sondeo fue bien). */
    public SpotifyException lastPollError() {
        return lastPollError;
    }

    private void setPlayback(Playback p) {
        snapshot = new Snapshot(p, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ sondeo

    public synchronized void startPolling(int intervalSeconds) {
        if (pollTask != null) pollTask.cancel(false);
        pollTask = exec.scheduleWithFixedDelay(this::pollSafe, 0, Math.max(1, intervalSeconds), TimeUnit.SECONDS);
    }

    public synchronized void stopPolling() {
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    /** Pide un sondeo inmediato (tras una accion) para que la UI refleje el cambio real. */
    public void refreshSoon() {
        exec.schedule(this::pollSafe, 600, TimeUnit.MILLISECONDS);
    }

    private void pollSafe() {
        synchronized (pollLock) {
            if (!auth.isLoggedIn() || System.currentTimeMillis() < backoffUntilMs) return;
            try {
                setPlayback(api.getPlayback());
                lastPollError = null;
            } catch (SpotifyException e) {
                lastPollError = e;
                if (e.kind() == SpotifyException.Kind.RATE_LIMITED) {
                    backoffUntilMs = System.currentTimeMillis() + Math.max(e.retryAfterMs(), 1000L);
                }
            } catch (RuntimeException e) {
                // un fallo inesperado no debe matar la tarea periodica
                lastPollError = new SpotifyException(SpotifyException.Kind.OTHER, 0, String.valueOf(e.getMessage()), 0L, e);
            }
        }
    }

    // ------------------------------------------------------------------ acciones

    public CompletableFuture<Void> togglePlay() {
        return action(() -> {
            Playback p = snapshot.playback();
            if (p.playing()) {
                setPlayback(p.withPlaying(false, progressMs()));
                api.pause();
            } else {
                withDevice(device -> api.play(null, device));
                setPlayback(p.withPlaying(true, progressMs()));
            }
        });
    }

    public CompletableFuture<Void> next() {
        return action(() -> withDevice(api::next));
    }

    public CompletableFuture<Void> previous() {
        return action(() -> withDevice(api::previous));
    }

    public CompletableFuture<Void> seek(long positionMs) {
        return action(() -> {
            setPlayback(snapshot.playback().withProgress(positionMs));
            api.seek(positionMs);
        });
    }

    public CompletableFuture<Void> setVolume(int percent) {
        return action(() -> {
            setPlayback(snapshot.playback().withVolume(percent));
            api.setVolume(percent);
        });
    }

    public CompletableFuture<Void> toggleShuffle() {
        return action(() -> {
            boolean on = !snapshot.playback().shuffle();
            setPlayback(snapshot.playback().withShuffle(on));
            api.setShuffle(on);
        });
    }

    /** off -> context (repetir playlist) -> track (repetir cancion) -> off. */
    public CompletableFuture<Void> cycleRepeat() {
        return action(() -> {
            String next = switch (snapshot.playback().repeat()) {
                case "off" -> "context";
                case "context" -> "track";
                default -> "off";
            };
            setPlayback(snapshot.playback().withRepeat(next));
            api.setRepeat(next);
        });
    }

    public CompletableFuture<Void> playContext(String contextUri) {
        JsonObject body = new JsonObject();
        body.addProperty("context_uri", contextUri);
        return action(() -> withDevice(device -> api.play(body, device)));
    }

    /** Reproduce una lista de canciones empezando por {@code offset}. */
    public CompletableFuture<Void> playTracks(List<String> uris, int offset) {
        JsonObject body = new JsonObject();
        JsonArray arr = new JsonArray();
        uris.forEach(arr::add);
        body.add("uris", arr);
        JsonObject off = new JsonObject();
        off.addProperty("position", Math.max(0, offset));
        body.add("offset", off);
        return action(() -> withDevice(device -> api.play(body, device)));
    }

    public CompletableFuture<List<Item>> playlists() {
        return CompletableFuture.supplyAsync(api::playlists, exec);
    }

    public CompletableFuture<List<Item>> search(String query) {
        return CompletableFuture.supplyAsync(() -> api.search(query), exec);
    }

    private CompletableFuture<Void> action(Runnable r) {
        return CompletableFuture.runAsync(r, exec).whenComplete((v, e) -> refreshSoon());
    }

    /**
     * Ejecuta {@code call} sobre el dispositivo activo. Si Spotify dice que no hay ninguno (por ejemplo la app
     * esta abierta pero en pausa hace rato), se reintenta sobre el primer dispositivo disponible.
     */
    private void withDevice(Consumer<String> call) {
        try {
            call.accept(null);
        } catch (SpotifyException e) {
            if (e.kind() != SpotifyException.Kind.NO_DEVICE) throw e;
            List<Device> devices = api.devices();
            if (devices.isEmpty()) throw e;
            Device pick = devices.stream().filter(Device::active).findFirst()
                    .orElseGet(() -> devices.stream().filter(d -> "Computer".equalsIgnoreCase(d.type())).findFirst()
                            .orElse(devices.get(0)));
            call.accept(pick.id());
        }
    }
}
