package com.orochi.spotifybar.client;

import com.orochi.spotifybar.config.SpotifyConfig;
import com.orochi.spotifybar.spotify.SpotifyAuth;
import com.orochi.spotifybar.spotify.SpotifyModels.Item;
import com.orochi.spotifybar.spotify.SpotifyModels.ItemKind;
import com.orochi.spotifybar.spotify.SpotifyModels.Playback;
import com.orochi.spotifybar.spotify.SpotifyModels.Track;
import com.orochi.spotifybar.spotify.SpotifyService;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * La "barra" de Spotify: controles de reproduccion, progreso, volumen y un selector de musica
 * (tus playlists o una busqueda). Si aun no hay sesion, muestra el asistente de conexion.
 */
public class SpotifyScreen extends Screen {
    private static final int ROW_HEIGHT = 24;
    private static final int RED = 0xFFFF5555;
    private static final int WHITE = 0xFFFFFFFF;

    private enum Tab { PLAYLISTS, SEARCH }

    private final SpotifyService svc = SpotifyClient.service();

    // diseno
    private int left;
    private int top;
    private int panelW;
    private int panelH;
    private int listTop;
    private int listBottom;
    private int barX;
    private int barY;
    private int barW;

    // estado
    private boolean builtLoggedIn;
    private Tab tab = Tab.PLAYLISTS;
    private List<Item> playlists = List.of();
    private List<Item> searchResults = List.of();
    private boolean playlistsLoaded;
    private boolean loading;
    private int scroll;
    private Component status = Component.empty();
    private int statusColor = SpotifyHud.GRAY;
    private boolean seeking;
    private double seekRatio;
    private List<FormattedCharSequence> helpLines = List.of();

    // widgets
    private EditBox searchBox;
    private EditBox clientIdBox;
    private Button playBtn;
    private Button shuffleBtn;
    private Button repeatBtn;
    private VolumeSlider volume;

    public SpotifyScreen() {
        super(Component.translatable("screen.spotifybar.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false; // la musica y el juego siguen; en multijugador tampoco se pausa
    }

    // ------------------------------------------------------------------ construccion

    @Override
    protected void init() {
        panelW = Math.min(340, width - 16);
        panelH = Math.min(262, height - 16);
        left = (width - panelW) / 2;
        top = (height - panelH) / 2;
        searchBox = null;
        clientIdBox = null;
        playBtn = null;
        shuffleBtn = null;
        repeatBtn = null;
        volume = null;

        builtLoggedIn = svc.isLoggedIn();
        if (builtLoggedIn) {
            initPlayer();
        } else {
            initLogin();
        }
    }

    private void initLogin() {
        int port = SpotifyConfig.REDIRECT_PORT.get();
        helpLines = font.split(Component.translatable("screen.spotifybar.login.help", SpotifyAuth.redirectUri(port)), panelW - 24);
        int y = top + 26 + helpLines.size() * 10 + 10;

        clientIdBox = new EditBox(font, left + 12, y, panelW - 24, 20, Component.translatable("screen.spotifybar.login.client_id"));
        clientIdBox.setMaxLength(64);
        clientIdBox.setHint(Component.translatable("screen.spotifybar.login.client_id"));
        clientIdBox.setValue(SpotifyConfig.CLIENT_ID.get());
        addRenderableWidget(clientIdBox);

        int half = (panelW - 24 - 6) / 2;
        addRenderableWidget(Button.builder(Component.translatable("screen.spotifybar.login.connect"), b -> connect())
                .bounds(left + 12, y + 26, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.spotifybar.login.copy_link"), b -> {
            String url = svc.pendingLoginUrl();
            if (url != null) {
                minecraft.keyboardHandler.setClipboard(url);
                setStatus(Component.translatable("screen.spotifybar.login.link_copied"), SpotifyHud.GREEN);
            }
        }).bounds(left + 12 + half + 6, y + 26, half, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
                .bounds(left + 12, top + panelH - 30, panelW - 24, 20).build());
    }

    private void initPlayer() {
        int x = left + 8;
        int y = top + 50;

        addRenderableWidget(Button.builder(Component.literal("|<"), b -> run(svc.previous()))
                .bounds(x, y, 28, 20).tooltip(Tooltip.create(Component.translatable("key.spotifybar.previous"))).build());
        x += 30;
        playBtn = addRenderableWidget(Button.builder(Component.literal(">"), b -> run(svc.togglePlay()))
                .bounds(x, y, 36, 20).tooltip(Tooltip.create(Component.translatable("key.spotifybar.play_pause"))).build());
        x += 38;
        addRenderableWidget(Button.builder(Component.literal(">|"), b -> run(svc.next()))
                .bounds(x, y, 28, 20).tooltip(Tooltip.create(Component.translatable("key.spotifybar.next"))).build());
        x += 34;
        shuffleBtn = addRenderableWidget(Button.builder(Component.translatable("screen.spotifybar.shuffle"), b -> run(svc.toggleShuffle()))
                .bounds(x, y, 26, 20).build());
        x += 28;
        repeatBtn = addRenderableWidget(Button.builder(Component.translatable("screen.spotifybar.repeat"), b -> run(svc.cycleRepeat()))
                .bounds(x, y, 26, 20).build());

        int volW = Math.min(100, panelW - (x + 30 - left) - 8);
        if (volW >= 50) {
            volume = addRenderableWidget(new VolumeSlider(left + panelW - 8 - volW, y, volW, 20,
                    svc.playback().device() == null ? 0.5D : svc.playback().device().volumePercent() / 100.0D));
        }

        // logout, discreto arriba a la derecha
        addRenderableWidget(Button.builder(Component.translatable("screen.spotifybar.disconnect"), b -> {
            svc.logout();
            playlists = List.of();
            searchResults = List.of();
            playlistsLoaded = false;
            clearWidgets();
            init();
        }).bounds(left + panelW - 78, top + 6, 70, 14).build());

        barX = left + 8 + 30;
        barW = panelW - 16 - 60;
        barY = top + 82;

        int tabY = top + 98;
        int tabW = Math.max(60, (panelW - 16 - 8) / 3);
        addRenderableWidget(Button.builder(Component.translatable("screen.spotifybar.tab.playlists"), b -> setTab(Tab.PLAYLISTS))
                .bounds(left + 8, tabY, tabW, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.spotifybar.tab.search"), b -> setTab(Tab.SEARCH))
                .bounds(left + 8 + tabW + 4, tabY, tabW, 20).build());

        int boxX = left + 8 + (tabW + 4) * 2;
        searchBox = new EditBox(font, boxX, tabY, left + panelW - 8 - boxX, 20, Component.translatable("screen.spotifybar.tab.search"));
        searchBox.setMaxLength(100);
        searchBox.setHint(Component.translatable("screen.spotifybar.search_hint"));
        searchBox.visible = tab == Tab.SEARCH;
        addRenderableWidget(searchBox);

        listTop = tabY + 26;
        listBottom = top + panelH - 20;

        if (!playlistsLoaded && !loading) {
            loadPlaylists();
        }
    }

    // ------------------------------------------------------------------ acciones

    private void connect() {
        String id = clientIdBox.getValue().trim();
        if (id.isEmpty()) {
            setStatus(Component.translatable("screen.spotifybar.login.client_id_missing"), RED);
            return;
        }
        SpotifyConfig.CLIENT_ID.set(id);
        SpotifyConfig.CLIENT_ID.save();
        setStatus(Component.translatable("screen.spotifybar.login.waiting"), SpotifyHud.GRAY);
        svc.beginLogin(id, SpotifyConfig.REDIRECT_PORT.get(), url -> Util.getPlatform().openUri(url));
    }

    private void setTab(Tab t) {
        tab = t;
        scroll = 0;
        searchBox.visible = t == Tab.SEARCH;
        searchBox.setFocused(t == Tab.SEARCH);
        if (t == Tab.SEARCH) {
            setFocused(searchBox);
        } else if (!playlistsLoaded && !loading) {
            loadPlaylists();
        }
    }

    private void loadPlaylists() {
        loading = true;
        svc.playlists().whenComplete((list, err) -> minecraft.execute(() -> {
            loading = false;
            if (err != null) {
                setError(err);
            } else {
                playlists = list;
                playlistsLoaded = true;
            }
        }));
    }

    private void doSearch() {
        String q = searchBox.getValue().trim();
        if (q.isEmpty() || loading) {
            return;
        }
        loading = true;
        scroll = 0;
        svc.search(q).whenComplete((list, err) -> minecraft.execute(() -> {
            loading = false;
            if (err != null) {
                setError(err);
            } else {
                searchResults = list;
                clearStatus();
            }
        }));
    }

    private List<Item> items() {
        return tab == Tab.SEARCH ? searchResults : playlists;
    }

    private void play(int index) {
        List<Item> list = items();
        Item item = list.get(index);
        if (item.kind() == ItemKind.PLAYLIST) {
            run(svc.playContext(item.uri()));
            return;
        }
        // Una cancion de la busqueda: se encola toda la lista de canciones desde la pulsada.
        List<String> uris = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).kind() == ItemKind.TRACK) {
                if (i == index) {
                    offset = uris.size();
                }
                uris.add(list.get(i).uri());
            }
        }
        run(svc.playTracks(uris, offset));
    }

    private void run(CompletableFuture<?> future) {
        future.whenComplete((ok, err) -> {
            if (err != null) {
                minecraft.execute(() -> setError(err));
            }
        });
    }

    private void setError(Throwable err) {
        setStatus(Errors.describe(err), RED);
    }

    private void setStatus(Component text, int color) {
        status = text;
        statusColor = color;
    }

    private void clearStatus() {
        status = Component.empty();
    }

    // ------------------------------------------------------------------ ciclo de vida

    @Override
    public void tick() {
        if (svc.isLoggedIn() != builtLoggedIn) {
            clearWidgets();
            init();
            clearStatus();
            return;
        }
        if (!builtLoggedIn) {
            if (svc.loginState() == SpotifyAuth.LoginState.FAILED && svc.loginError() != null) {
                setStatus(Component.translatable("screen.spotifybar.login.failed", svc.loginError()), RED);
            }
            return;
        }
        Playback pb = svc.playback();
        if (playBtn != null) {
            playBtn.setMessage(Component.literal(pb.playing() ? "||" : ">"));
        }
        if (shuffleBtn != null) {
            shuffleBtn.setMessage(Component.translatable("screen.spotifybar.shuffle")
                    .withStyle(pb.shuffle() ? ChatFormatting.GREEN : ChatFormatting.WHITE));
        }
        if (repeatBtn != null) {
            repeatBtn.setMessage(Component.translatable("screen.spotifybar.repeat")
                    .withStyle(switch (pb.repeat()) {
                        case "context" -> ChatFormatting.GREEN;
                        case "track" -> ChatFormatting.YELLOW;
                        default -> ChatFormatting.WHITE;
                    }));
        }
        if (volume != null) {
            volume.active = pb.device() != null && pb.device().supportsVolume();
            if (pb.device() != null) {
                volume.sync(pb.device().volumePercent() / 100.0D);
            }
        }
    }

    // ------------------------------------------------------------------ dibujo

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g);
        g.fill(left, top, left + panelW, top + panelH, 0xE0101010);
        g.fill(left, top, left + panelW, top + 1, SpotifyHud.GREEN);
        g.fill(left, top + panelH - 1, left + panelW, top + panelH, SpotifyHud.GREEN);
        g.fill(left, top, left + 1, top + panelH, SpotifyHud.GREEN);
        g.fill(left + panelW - 1, top, left + panelW, top + panelH, SpotifyHud.GREEN);

        g.drawString(font, title, left + 8, top + 8, SpotifyHud.GREEN, false);
        if (builtLoggedIn) {
            renderPlayer(g, mouseX, mouseY);
        } else {
            int y = top + 26;
            for (FormattedCharSequence line : helpLines) {
                g.drawString(font, line, left + 12, y, WHITE, false);
                y += 10;
            }
        }
        if (!status.getString().isEmpty()) {
            g.drawString(font, SpotifyHud.fit(font, status.getString(), panelW - 16), left + 8, top + panelH - (builtLoggedIn ? 14 : 44), statusColor, false);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private void renderPlayer(GuiGraphics g, int mouseX, int mouseY) {
        Playback pb = svc.playback();
        Track track = pb.track();

        // cabecera: portada + titulo/artista/dispositivo
        int cover = 36;
        int cx = left + 8;
        int cy = top + 8 + 10;
        if (track != null) {
            SpotifyHud.drawCover(g, track, cx, cy - 6, cover);
        } else {
            g.fill(cx, cy - 6, cx + cover, cy - 6 + cover, 0xFF303030);
        }
        int tx = cx + cover + 8;
        int tw = left + panelW - 8 - tx;
        if (track != null) {
            g.drawString(font, SpotifyHud.fit(font, track.name(), tw), tx, cy - 4, WHITE, false);
            g.drawString(font, SpotifyHud.fit(font, track.artists(), tw), tx, cy + 7, SpotifyHud.GRAY, false);
        } else {
            g.drawString(font, Component.translatable("screen.spotifybar.nothing_playing"), tx, cy - 4, SpotifyHud.GRAY, false);
        }
        if (pb.device() != null) {
            g.drawString(font, SpotifyHud.fit(font, Component.translatable("screen.spotifybar.device", pb.device().name()).getString(), tw),
                    tx, cy + 18, 0xFF707070, false);
        }

        // barra de progreso
        long duration = track == null ? 0L : Math.max(1L, track.durationMs());
        float ratio = duration == 0L ? 0.0F : (seeking ? (float) seekRatio : Math.min(1.0F, svc.progressMs() / (float) duration));
        long shown = seeking ? (long) (seekRatio * duration) : svc.progressMs();
        g.drawString(font, SpotifyHud.time(duration == 0L ? 0L : shown), left + 8, barY - 2, SpotifyHud.GRAY, false);
        g.drawString(font, SpotifyHud.time(duration), barX + barW + 4, barY - 2, SpotifyHud.GRAY, false);
        g.fill(barX, barY, barX + barW, barY + 5, 0xFF404040);
        g.fill(barX, barY, barX + (int) (barW * ratio), barY + 5, SpotifyHud.GREEN);
        int knob = barX + (int) (barW * ratio);
        g.fill(knob - 2, barY - 2, knob + 2, barY + 7, WHITE);

        // lista
        g.fill(left + 8, listTop, left + panelW - 8, listBottom, 0x60000000);
        List<Item> list = items();
        g.enableScissor(left + 8, listTop, left + panelW - 8, listBottom);
        String currentUri = track == null ? null : track.uri();
        for (int i = 0; i < list.size(); i++) {
            int rowY = listTop + i * ROW_HEIGHT - scroll;
            if (rowY + ROW_HEIGHT < listTop || rowY > listBottom) {
                continue;
            }
            Item it = list.get(i);
            boolean hover = mouseX >= left + 8 && mouseX < left + panelW - 8 && mouseY >= Math.max(rowY, listTop) && mouseY < Math.min(rowY + ROW_HEIGHT, listBottom);
            boolean current = it.uri().equals(currentUri) || it.uri().equals(pb.contextUri());
            if (hover) {
                g.fill(left + 8, rowY, left + panelW - 8, rowY + ROW_HEIGHT, 0x40FFFFFF);
            }
            if (current) {
                g.fill(left + 8, rowY, left + 11, rowY + ROW_HEIGHT, SpotifyHud.GREEN);
            }
            int w = panelW - 16 - 14;
            String prefix = it.kind() == ItemKind.PLAYLIST ? "" : "♪ ";
            g.drawString(font, SpotifyHud.fit(font, prefix + it.name(), w), left + 14, rowY + 3, current ? SpotifyHud.GREEN : WHITE, false);
            g.drawString(font, SpotifyHud.fit(font, it.subtitle(), w), left + 14, rowY + 13, 0xFF808080, false);
        }
        g.disableScissor();
        if (list.isEmpty()) {
            Component empty = Component.translatable(loading ? "screen.spotifybar.loading"
                    : tab == Tab.SEARCH ? "screen.spotifybar.search_empty" : "screen.spotifybar.playlists_empty");
            g.drawString(font, empty, left + 14, listTop + 8, SpotifyHud.GRAY, false);
        }
    }

    // ------------------------------------------------------------------ entrada

    private boolean overBar(double mx, double my) {
        return builtLoggedIn && mx >= barX - 3 && mx <= barX + barW + 3 && my >= barY - 4 && my <= barY + 9;
    }

    private void updateSeek(double mx) {
        seekRatio = Math.max(0.0D, Math.min(1.0D, (mx - barX) / barW));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        if (button == 0 && svc.playback().track() != null && overBar(mx, my)) {
            seeking = true;
            updateSeek(mx);
            return true;
        }
        if (button == 0 && builtLoggedIn && mx >= left + 8 && mx < left + panelW - 8 && my >= listTop && my < listBottom) {
            int index = (int) ((my - listTop + scroll) / ROW_HEIGHT);
            if (index >= 0 && index < items().size()) {
                play(index);
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (seeking) {
            updateSeek(mx);
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        if (seeking && button == 0) {
            seeking = false;
            Track t = svc.playback().track();
            if (t != null) {
                run(svc.seek((long) (seekRatio * t.durationMs())));
            }
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (builtLoggedIn && mx >= left + 8 && mx < left + panelW - 8 && my >= listTop && my < listBottom) {
            int max = Math.max(0, items().size() * ROW_HEIGHT - (listBottom - listTop));
            scroll = (int) Math.max(0, Math.min(max, scroll - delta * ROW_HEIGHT));
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        boolean typing = (searchBox != null && searchBox.visible && searchBox.isFocused())
                || (clientIdBox != null && clientIdBox.isFocused());
        if (typing && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            if (searchBox != null && searchBox.isFocused()) {
                doSearch();
            } else if (clientIdBox != null) {
                connect();
            }
            return true;
        }
        if (!typing && Keybinds.OPEN.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ slider de volumen

    private final class VolumeSlider extends AbstractSliderButton {
        private boolean dragging;

        VolumeSlider(int x, int y, int w, int h, double value) {
            super(x, y, w, h, Component.empty(), value);
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.translatable("screen.spotifybar.volume", (int) Math.round(value * 100.0D)));
        }

        @Override
        protected void applyValue() {
            // el volumen real se envia al soltar el raton para no saturar la API
        }

        @Override
        public void onClick(double mx, double my) {
            dragging = true;
            super.onClick(mx, my);
        }

        @Override
        public void onRelease(double mx, double my) {
            super.onRelease(mx, my);
            dragging = false;
            run(svc.setVolume((int) Math.round(value * 100.0D)));
        }

        void sync(double v) {
            if (!dragging) {
                value = v;
                updateMessage();
            }
        }
    }
}
