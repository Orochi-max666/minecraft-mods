package com.orochi.spotifybar.client;

import com.orochi.spotifybar.spotify.SpotifyService;

import java.nio.file.Path;

/** Punto de acceso unico al {@link SpotifyService} del cliente. */
public final class SpotifyClient {
    private static SpotifyService instance;

    private SpotifyClient() {}

    public static synchronized SpotifyService service() {
        if (instance == null) {
            // Fuera de la carpeta del juego para que no acabe por error dentro de un modpack exportado.
            Path tokens = Path.of(System.getProperty("user.home"), ".spotifybar", "tokens.json");
            instance = new SpotifyService(tokens);
        }
        return instance;
    }
}
