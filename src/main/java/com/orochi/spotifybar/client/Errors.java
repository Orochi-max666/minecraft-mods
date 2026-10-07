package com.orochi.spotifybar.client;

import com.orochi.spotifybar.spotify.SpotifyException;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Convierte excepciones de Spotify en mensajes traducidos para el jugador. */
final class Errors {
    private Errors() {}

    static Component describe(Throwable error) {
        Throwable t = error;
        while ((t instanceof CompletionException || t instanceof java.util.concurrent.ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (t instanceof SpotifyException se) {
            return switch (se.kind()) {
                case OTHER -> Component.translatable("screen.spotifybar.error.other", String.valueOf(se.getMessage()));
                default -> Component.translatable("screen.spotifybar.error." + se.kind().name().toLowerCase(Locale.ROOT));
            };
        }
        return Component.translatable("screen.spotifybar.error.other", String.valueOf(t.getMessage()));
    }

    /** Para los atajos de teclado: si la accion falla, lo muestra sobre la barra de acciones. */
    static void notifyOnError(CompletableFuture<?> future) {
        future.whenComplete((ok, err) -> {
            if (err != null) {
                Minecraft mc = Minecraft.getInstance();
                mc.execute(() -> mc.gui.setOverlayMessage(describe(err), false));
            }
        });
    }
}
