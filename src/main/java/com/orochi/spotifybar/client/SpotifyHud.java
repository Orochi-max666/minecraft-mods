package com.orochi.spotifybar.client;

import com.orochi.spotifybar.config.SpotifyConfig;
import com.orochi.spotifybar.config.SpotifyConfig.HudMode;
import com.orochi.spotifybar.spotify.SpotifyModels.Playback;
import com.orochi.spotifybar.spotify.SpotifyModels.Track;
import com.orochi.spotifybar.spotify.SpotifyService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/** Barra compacta con la cancion actual, siempre visible en una esquina. */
public final class SpotifyHud implements IGuiOverlay {
    static final int GREEN = 0xFF1DB954;
    static final int GRAY = 0xFFB3B3B3;

    private static final int WIDTH = 170;
    private static final int HEIGHT = 40;
    private static final int MARGIN = 6;
    private static final int COVER = 32;
    private static final long SHOW_ON_CHANGE_MS = 5000L;

    private String lastUri;
    private long shownUntil;

    @Override
    public void render(ForgeGui gui, GuiGraphics g, float partialTick, int screenWidth, int screenHeight) {
        Minecraft mc = Minecraft.getInstance();
        HudMode mode = SpotifyConfig.HUD_MODE.get();
        if (mode == HudMode.OFF || mc.options.hideGui || mc.options.renderDebug || mc.screen instanceof SpotifyScreen) {
            return;
        }
        SpotifyService svc = SpotifyClient.service();
        if (!svc.isLoggedIn()) {
            return;
        }
        Playback pb = svc.playback();
        Track track = pb.track();
        if (track == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (!track.uri().equals(lastUri)) {
            lastUri = track.uri();
            shownUntil = now + SHOW_ON_CHANGE_MS;
        }
        if (mode == HudMode.ON_TRACK_CHANGE && now > shownUntil) {
            return;
        }

        float scale = SpotifyConfig.HUD_SCALE.get().floatValue();
        int viewW = (int) (screenWidth / scale);
        int viewH = (int) (screenHeight / scale);
        int x;
        int y;
        switch (SpotifyConfig.HUD_CORNER.get()) {
            case TOP_LEFT -> {
                x = MARGIN;
                y = MARGIN;
            }
            case BOTTOM_LEFT -> {
                x = MARGIN;
                y = viewH - HEIGHT - MARGIN;
            }
            case BOTTOM_RIGHT -> {
                x = viewW - WIDTH - MARGIN;
                y = viewH - HEIGHT - MARGIN;
            }
            default -> {
                x = viewW - WIDTH - MARGIN;
                y = MARGIN;
            }
        }

        Font font = mc.font;
        g.pose().pushPose();
        g.pose().scale(scale, scale, 1.0F);

        g.fill(x, y, x + WIDTH, y + HEIGHT, 0xB0101010);
        drawCover(g, track, x + 4, y + 4, COVER);

        int tx = x + COVER + 10;
        int tw = WIDTH - COVER - 16;
        g.drawString(font, fit(font, track.name(), tw), tx, y + 6, 0xFFFFFFFF, false);
        g.drawString(font, fit(font, track.artists(), tw), tx, y + 17, GRAY, false);

        long duration = Math.max(1L, track.durationMs());
        float ratio = Math.min(1.0F, svc.progressMs() / (float) duration);
        int barY = y + HEIGHT - 8;
        int barW = WIDTH - COVER - 16;
        g.fill(tx, barY, tx + barW, barY + 3, 0xFF404040);
        g.fill(tx, barY, tx + (int) (barW * ratio), barY + 3, GREEN);
        if (!pb.playing()) {
            g.drawString(font, "||", x + WIDTH - 12, y + 4, GRAY, false);
        }

        g.pose().popPose();
    }

    static void drawCover(GuiGraphics g, Track track, int x, int y, int size) {
        ResourceLocation tex = CoverTexture.get(track.imageUrl());
        if (tex == null) {
            g.fill(x, y, x + size, y + size, 0xFF303030);
            return;
        }
        int s = CoverTexture.size();
        g.blit(tex, x, y, size, size, 0.0F, 0.0F, s, s, s, s);
    }

    static String fit(Font font, String text, int maxWidth) {
        if (font.width(text) <= maxWidth) {
            return text;
        }
        return font.plainSubstrByWidth(text, maxWidth - font.width("...")) + "...";
    }

    static String time(long ms) {
        long s = Math.max(0L, ms) / 1000L;
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }
}
