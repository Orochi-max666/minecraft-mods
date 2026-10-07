package com.orochi.spotifybar.client;

import com.orochi.spotifybar.SpotifyBarMod;
import com.orochi.spotifybar.config.SpotifyConfig;
import com.orochi.spotifybar.spotify.SpotifyModels.Item;
import com.orochi.spotifybar.spotify.SpotifyModels.Playback;
import com.orochi.spotifybar.spotify.SpotifyModels.Track;
import com.orochi.spotifybar.spotify.SpotifyService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Tira ancha de Spotify que aparece junto a cualquier pantalla de contenedor (inventario, cofres, crafteo...).
 * En esas pantallas el cursor esta libre, asi que la tira es totalmente clicable: controles, progreso,
 * volumen y un desplegable con tus playlists.
 */
@Mod.EventBusSubscriber(modid = SpotifyBarMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class InventoryBar {
    private static final int HEIGHT = 40;
    private static final int MAX_WIDTH = 400;
    private static final int GAP = 3;
    private static final int ROW = 14;
    private static final int MAX_ROWS = 7;
    private static final int DROPDOWN_WIDTH = 200;
    private static final int GREEN = SpotifyHud.GREEN;
    private static final int GRAY = SpotifyHud.GRAY;
    private static final int WHITE = 0xFFFFFFFF;

    private record Rect(int x, int y, int w, int h) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    /** Posiciones de todos los elementos de la tira; se recalcula en cada evento (es barato). */
    private record Layout(Rect strip, Rect cover, int textX, int textW, Rect prev, Rect play, Rect next,
                          Rect shuffle, Rect repeat, Rect list, Rect volume, Rect bar, Rect dropdown) {}

    // estado (solo se toca desde el hilo de render / eventos de pantalla)
    private static Screen lastScreen;
    private static boolean open;
    private static int scroll;
    private static List<Item> playlists = List.of();
    private static boolean playlistsLoaded;
    private static boolean loading;
    private static boolean seeking;
    private static double seekRatio;
    private static boolean draggingVolume;
    private static double volumeValue;
    private static Component error;
    private static long errorUntil;

    private InventoryBar() {}

    // ------------------------------------------------------------------ utilidades

    private static boolean applies(Screen screen) {
        return screen instanceof AbstractContainerScreen<?>
                && SpotifyConfig.INVENTORY_BAR.get()
                && SpotifyClient.service().isLoggedIn();
    }

    /** Reinicia el estado transitorio cuando se abre una pantalla distinta. */
    private static void sync(Screen screen) {
        if (screen != lastScreen) {
            lastScreen = screen;
            open = false;
            scroll = 0;
            seeking = false;
            draggingVolume = false;
        }
    }

    private static Layout layout(AbstractContainerScreen<?> screen) {
        int sw = screen.width;
        int sh = screen.height;
        int w = Math.min(sw - 8, MAX_WIDTH);
        int x0 = (sw - w) / 2;

        // Encima de la ventana; si no cabe, debajo; si tampoco, pegada arriba. Las pestanas del modo
        // creativo sobresalen 28 px por arriba y por abajo.
        int extra = screen instanceof CreativeModeInventoryScreen ? 28 : 0;
        int guiTop = screen.getGuiTop() - extra;
        int guiBottom = screen.getGuiTop() + screen.getYSize() + extra;
        int y;
        if (guiTop >= HEIGHT + GAP + 1) {
            y = guiTop - HEIGHT - GAP;
        } else if (sh - guiBottom >= HEIGHT + GAP + 1) {
            y = guiBottom + GAP;
        } else {
            y = 2;
        }
        Rect strip = new Rect(x0, y, w, HEIGHT);

        Rect cover = new Rect(x0 + 4, y + 4, 32, 32);
        int right = x0 + w - 4;

        // de derecha a izquierda: volumen, y a su izquierda el grupo de botones
        boolean showVolume = w >= 330;
        Rect volume = null;
        int clusterRight = right;
        if (showVolume) {
            volume = new Rect(right - 54, y + 11, 54, 8);
            clusterRight = volume.x() - 8;
        }
        int by = y + 5;
        Rect list = new Rect(clusterRight - 52, by, 52, 16);
        Rect repeat = new Rect(list.x() - 20, by, 18, 16);
        Rect shuffle = new Rect(repeat.x() - 20, by, 18, 16);
        Rect next = new Rect(shuffle.x() - 20, by, 18, 16);
        Rect play = new Rect(next.x() - 20, by, 18, 16);
        Rect prev = new Rect(play.x() - 20, by, 18, 16);

        int textX = cover.x() + cover.w() + 6;
        int textW = Math.max(30, prev.x() - 6 - textX);

        // barra de progreso entre las etiquetas de tiempo
        Rect bar = new Rect(textX + 26, y + HEIGHT - 10, right - 26 - (textX + 26), 4);

        // desplegable de playlists: debajo de la tira, o encima si no cabe
        int rows = Math.max(1, Math.min(MAX_ROWS, playlists.size()));
        int dh = rows * ROW + 4;
        int dw = Math.min(DROPDOWN_WIDTH, w);
        int dx = Math.max(x0, Math.min(list.x() + list.w() - dw, x0 + w - dw));
        int dy = y + HEIGHT + 2 + dh <= sh ? y + HEIGHT + 2 : y - dh - 2;
        Rect dropdown = new Rect(dx, dy, dw, dh);

        return new Layout(strip, cover, textX, textW, prev, play, next, shuffle, repeat, list, volume, bar, dropdown);
    }

    private static void run(CompletableFuture<?> future) {
        future.whenComplete((ok, err) -> {
            if (err != null) {
                Minecraft.getInstance().execute(() -> {
                    error = Errors.describe(err);
                    errorUntil = System.currentTimeMillis() + 5000L;
                });
            }
        });
    }

    private static void loadPlaylists() {
        if (loading) {
            return;
        }
        loading = true;
        SpotifyClient.service().playlists().whenComplete((result, err) -> Minecraft.getInstance().execute(() -> {
            loading = false;
            if (err != null) {
                error = Errors.describe(err);
                errorUntil = System.currentTimeMillis() + 5000L;
            } else {
                playlists = result;
                playlistsLoaded = true;
            }
        }));
    }

    private static double ratio(double mouseX, Rect r) {
        return Math.max(0.0D, Math.min(1.0D, (mouseX - r.x()) / r.w()));
    }

    // ------------------------------------------------------------------ dibujo

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!applies(event.getScreen())) {
            return;
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) event.getScreen();
        sync(screen);
        Layout l = layout(screen);

        Minecraft mc = Minecraft.getInstance();
        Font font = mc.font;
        GuiGraphics g = event.getGuiGraphics();
        int mx = event.getMouseX();
        int my = event.getMouseY();

        SpotifyService svc = SpotifyClient.service();
        Playback pb = svc.playback();
        Track track = pb.track();

        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 400.0F); // por encima de los objetos del inventario

        Rect s = l.strip();
        g.fill(s.x(), s.y(), s.x() + s.w(), s.y() + s.h(), 0xE0101010);
        g.fill(s.x(), s.y(), s.x() + s.w(), s.y() + 1, GREEN);

        // portada + texto
        if (track != null) {
            SpotifyHud.drawCover(g, track, l.cover().x(), l.cover().y(), l.cover().w());
        } else {
            g.fill(l.cover().x(), l.cover().y(), l.cover().x() + l.cover().w(), l.cover().y() + l.cover().h(), 0xFF303030);
        }
        boolean showError = error != null && System.currentTimeMillis() < errorUntil;
        if (track != null) {
            g.drawString(font, SpotifyHud.fit(font, track.name(), l.textW()), l.textX(), s.y() + 7, WHITE, false);
            if (showError) {
                g.drawString(font, SpotifyHud.fit(font, error.getString(), l.textW()), l.textX(), s.y() + 18, 0xFFFF5555, false);
            } else {
                g.drawString(font, SpotifyHud.fit(font, track.artists(), l.textW()), l.textX(), s.y() + 18, GRAY, false);
            }
        } else {
            Component msg = showError ? error : Component.translatable("screen.spotifybar.inv.nothing");
            g.drawString(font, SpotifyHud.fit(font, msg.getString(), l.textW()), l.textX(), s.y() + 12, showError ? 0xFFFF5555 : GRAY, false);
        }

        // botones
        button(g, font, l.prev(), "|<", WHITE, mx, my);
        button(g, font, l.play(), pb.playing() ? "||" : ">", WHITE, mx, my);
        button(g, font, l.next(), ">|", WHITE, mx, my);
        button(g, font, l.shuffle(), Component.translatable("screen.spotifybar.shuffle").getString(), pb.shuffle() ? GREEN : WHITE, mx, my);
        int repeatColor = switch (pb.repeat()) {
            case "context" -> GREEN;
            case "track" -> 0xFFFFD84D;
            default -> WHITE;
        };
        button(g, font, l.repeat(), Component.translatable("screen.spotifybar.repeat").getString(), repeatColor, mx, my);
        button(g, font, l.list(), Component.translatable("screen.spotifybar.inv.playlists").getString(), open ? GREEN : WHITE, mx, my);

        // volumen
        if (l.volume() != null) {
            Rect v = l.volume();
            boolean supported = pb.device() != null && pb.device().supportsVolume();
            double value = draggingVolume ? volumeValue : (pb.device() == null ? 0.0D : pb.device().volumePercent() / 100.0D);
            g.fill(v.x(), v.y() + 2, v.x() + v.w(), v.y() + 6, 0xFF404040);
            g.fill(v.x(), v.y() + 2, v.x() + (int) (v.w() * value), v.y() + 6, supported ? GREEN : 0xFF606060);
            int knob = v.x() + (int) (v.w() * value);
            g.fill(knob - 1, v.y(), knob + 2, v.y() + 8, supported ? WHITE : 0xFF808080);
        }

        // progreso
        Rect b = l.bar();
        long duration = track == null ? 0L : Math.max(1L, track.durationMs());
        double progress = duration == 0L ? 0.0D : (seeking ? seekRatio : Math.min(1.0D, svc.progressMs() / (double) duration));
        long shown = seeking ? (long) (seekRatio * duration) : svc.progressMs();
        g.drawString(font, SpotifyHud.time(duration == 0L ? 0L : shown), l.textX(), b.y() - 2, GRAY, false);
        g.drawString(font, SpotifyHud.time(duration), b.x() + b.w() + 4, b.y() - 2, GRAY, false);
        g.fill(b.x(), b.y(), b.x() + b.w(), b.y() + b.h(), 0xFF404040);
        g.fill(b.x(), b.y(), b.x() + (int) (b.w() * progress), b.y() + b.h(), GREEN);
        int knob = b.x() + (int) (b.w() * progress);
        g.fill(knob - 1, b.y() - 2, knob + 2, b.y() + b.h() + 2, WHITE);

        if (open) {
            drawDropdown(g, font, l, mx, my, pb);
        }
        g.pose().popPose();
    }

    private static void button(GuiGraphics g, Font font, Rect r, String label, int color, int mx, int my) {
        g.fill(r.x(), r.y(), r.x() + r.w(), r.y() + r.h(), r.contains(mx, my) ? 0x60FFFFFF : 0x30FFFFFF);
        g.drawString(font, label, r.x() + (r.w() - font.width(label)) / 2, r.y() + (r.h() - 8) / 2, color, false);
    }

    private static void drawDropdown(GuiGraphics g, Font font, Layout l, int mx, int my, Playback pb) {
        Rect d = l.dropdown();
        g.fill(d.x() - 1, d.y() - 1, d.x() + d.w() + 1, d.y() + d.h() + 1, GREEN);
        g.fill(d.x(), d.y(), d.x() + d.w(), d.y() + d.h(), 0xF0101010);

        if (playlists.isEmpty()) {
            String msg = Component.translatable(loading || !playlistsLoaded ? "screen.spotifybar.inv.loading" : "screen.spotifybar.playlists_empty").getString();
            g.drawString(font, msg, d.x() + 6, d.y() + 5, GRAY, false);
            return;
        }
        g.enableScissor(d.x(), d.y() + 2, d.x() + d.w(), d.y() + d.h() - 2);
        for (int i = 0; i < playlists.size(); i++) {
            int ry = d.y() + 2 + i * ROW - scroll;
            if (ry + ROW < d.y() || ry > d.y() + d.h()) {
                continue;
            }
            Item it = playlists.get(i);
            boolean current = it.uri().equals(pb.contextUri());
            if (d.contains(mx, my) && my >= ry && my < ry + ROW) {
                g.fill(d.x(), ry, d.x() + d.w(), ry + ROW, 0x40FFFFFF);
            }
            g.drawString(font, SpotifyHud.fit(font, it.name(), d.w() - 12), d.x() + 6, ry + 3, current ? GREEN : WHITE, false);
        }
        g.disableScissor();
    }

    // ------------------------------------------------------------------ entrada

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!applies(event.getScreen())) {
            return;
        }
        AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) event.getScreen();
        sync(screen);
        Layout l = layout(screen);
        double mx = event.getMouseX();
        double my = event.getMouseY();

        boolean inDropdown = open && l.dropdown().contains(mx, my);
        boolean inStrip = l.strip().contains(mx, my);
        if (!inDropdown && !inStrip) {
            open = false; // un clic fuera cierra el desplegable pero sigue su camino normal
            return;
        }
        // Se cancela siempre: un clic "fuera" de la ventana con un objeto en el cursor lo tiraria al suelo.
        event.setCanceled(true);
        if (event.getButton() != 0) {
            return;
        }

        SpotifyService svc = SpotifyClient.service();
        if (inDropdown) {
            int index = (int) ((my - l.dropdown().y() - 2 + scroll) / ROW);
            if (index >= 0 && index < playlists.size()) {
                run(svc.playContext(playlists.get(index).uri()));
                open = false;
            }
            return;
        }

        if (l.prev().contains(mx, my)) {
            run(svc.previous());
        } else if (l.play().contains(mx, my)) {
            run(svc.togglePlay());
        } else if (l.next().contains(mx, my)) {
            run(svc.next());
        } else if (l.shuffle().contains(mx, my)) {
            run(svc.toggleShuffle());
        } else if (l.repeat().contains(mx, my)) {
            run(svc.cycleRepeat());
        } else if (l.list().contains(mx, my)) {
            open = !open;
            scroll = 0;
            if (open && !playlistsLoaded) {
                loadPlaylists();
            }
        } else if (l.volume() != null && padded(l.volume(), 3).contains(mx, my)) {
            Playback pb = svc.playback();
            if (pb.device() != null && pb.device().supportsVolume()) {
                draggingVolume = true;
                volumeValue = ratio(mx, l.volume());
            }
        } else if (padded(l.bar(), 4).contains(mx, my) && svc.playback().track() != null) {
            seeking = true;
            seekRatio = ratio(mx, l.bar());
        }
    }

    private static Rect padded(Rect r, int pad) {
        return new Rect(r.x() - pad, r.y() - pad, r.w() + pad * 2, r.h() + pad * 2);
    }

    @SubscribeEvent
    public static void onMouseDragged(ScreenEvent.MouseDragged.Pre event) {
        if (!applies(event.getScreen()) || !(seeking || draggingVolume)) {
            return;
        }
        Layout l = layout((AbstractContainerScreen<?>) event.getScreen());
        if (seeking) {
            seekRatio = ratio(event.getMouseX(), l.bar());
        } else if (l.volume() != null) {
            volumeValue = ratio(event.getMouseX(), l.volume());
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseReleased(ScreenEvent.MouseButtonReleased.Pre event) {
        if (!applies(event.getScreen()) || !(seeking || draggingVolume) || event.getButton() != 0) {
            return;
        }
        SpotifyService svc = SpotifyClient.service();
        if (seeking) {
            seeking = false;
            Track t = svc.playback().track();
            if (t != null) {
                run(svc.seek((long) (seekRatio * t.durationMs())));
            }
        }
        if (draggingVolume) {
            draggingVolume = false;
            run(svc.setVolume((int) Math.round(volumeValue * 100.0D)));
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseScrolled(ScreenEvent.MouseScrolled.Pre event) {
        if (!applies(event.getScreen())) {
            return;
        }
        Layout l = layout((AbstractContainerScreen<?>) event.getScreen());
        if (open && l.dropdown().contains(event.getMouseX(), event.getMouseY())) {
            int max = Math.max(0, playlists.size() * ROW - (l.dropdown().h() - 4));
            scroll = (int) Math.max(0, Math.min(max, scroll - event.getScrollDelta() * ROW));
            event.setCanceled(true);
        }
    }
}
