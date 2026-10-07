package com.orochi.spotifybar.client;

import com.orochi.spotifybar.SpotifyBarMod;
import com.orochi.spotifybar.config.SpotifyConfig;
import com.orochi.spotifybar.spotify.SpotifyService;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** Registro de teclas/overlay (bus del mod) y reaccion a teclas y a entrar/salir de un mundo (bus de Forge). */
public final class ClientEvents {
    private ClientEvents() {}

    @Mod.EventBusSubscriber(modid = SpotifyBarMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        private ModBus() {}

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(Keybinds.OPEN);
            event.register(Keybinds.PLAY_PAUSE);
            event.register(Keybinds.NEXT);
            event.register(Keybinds.PREVIOUS);
        }

        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAboveAll("spotify_bar", new SpotifyHud());
        }
    }

    @Mod.EventBusSubscriber(modid = SpotifyBarMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {
        private ForgeBus() {}

        @SubscribeEvent
        public static void onTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || mc.player == null) {
                return;
            }
            SpotifyService svc = SpotifyClient.service();

            while (Keybinds.OPEN.consumeClick()) {
                if (mc.screen == null) {
                    mc.setScreen(new SpotifyScreen());
                }
            }
            // Los atajos de reproduccion solo actuan con sesion iniciada y sin ninguna pantalla abierta.
            while (Keybinds.PLAY_PAUSE.consumeClick()) {
                if (svc.isLoggedIn()) {
                    Errors.notifyOnError(svc.togglePlay());
                }
            }
            while (Keybinds.NEXT.consumeClick()) {
                if (svc.isLoggedIn()) {
                    Errors.notifyOnError(svc.next());
                }
            }
            while (Keybinds.PREVIOUS.consumeClick()) {
                if (svc.isLoggedIn()) {
                    Errors.notifyOnError(svc.previous());
                }
            }
        }

        @SubscribeEvent
        public static void onLogin(ClientPlayerNetworkEvent.LoggingIn event) {
            SpotifyClient.service().startPolling(SpotifyConfig.POLL_SECONDS.get());
        }

        @SubscribeEvent
        public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
            SpotifyClient.service().stopPolling();
        }
    }
}
