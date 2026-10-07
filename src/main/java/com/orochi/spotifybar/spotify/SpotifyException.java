package com.orochi.spotifybar.spotify;

/** Error al hablar con Spotify. {@link Kind} permite a la UI mostrar un mensaje traducido. */
public class SpotifyException extends RuntimeException {
    public enum Kind {
        NOT_LOGGED_IN,
        AUTH_FAILED,
        NO_DEVICE,
        PREMIUM_REQUIRED,
        RATE_LIMITED,
        NETWORK,
        OTHER
    }

    private final Kind kind;
    private final int status;
    private final long retryAfterMs;

    public SpotifyException(Kind kind, int status, String message) {
        this(kind, status, message, 0L, null);
    }

    public SpotifyException(Kind kind, int status, String message, long retryAfterMs, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.status = status;
        this.retryAfterMs = retryAfterMs;
    }

    public Kind kind() {
        return kind;
    }

    public int status() {
        return status;
    }

    public long retryAfterMs() {
        return retryAfterMs;
    }
}
