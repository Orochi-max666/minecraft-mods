package com.orochi.spotifybar.config;

import net.minecraftforge.common.ForgeConfigSpec;

/** Configuracion de cliente ({@code config/spotifybar-client.toml}). */
public final class SpotifyConfig {
    public enum HudMode { ALWAYS, ON_TRACK_CHANGE, OFF }

    public enum HudCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<String> CLIENT_ID;
    public static final ForgeConfigSpec.IntValue REDIRECT_PORT;
    public static final ForgeConfigSpec.IntValue POLL_SECONDS;
    public static final ForgeConfigSpec.EnumValue<HudMode> HUD_MODE;
    public static final ForgeConfigSpec.EnumValue<HudCorner> HUD_CORNER;
    public static final ForgeConfigSpec.DoubleValue HUD_SCALE;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.push("spotify");
        CLIENT_ID = b.comment("Client ID de tu app en https://developer.spotify.com/dashboard (se puede pegar desde el juego).")
                .define("clientId", "");
        REDIRECT_PORT = b.comment("Puerto del servidor local temporal del login. La Redirect URI de tu app debe ser",
                        "http://127.0.0.1:<puerto>/callback")
                .defineInRange("redirectPort", 8888, 1024, 65535);
        POLL_SECONDS = b.comment("Cada cuantos segundos se consulta a Spotify que se esta reproduciendo.")
                .defineInRange("pollSeconds", 3, 1, 30);
        b.pop();

        b.push("hud");
        HUD_MODE = b.comment("ALWAYS: siempre visible | ON_TRACK_CHANGE: solo unos segundos al cambiar de cancion | OFF: oculto")
                .defineEnum("mode", HudMode.ALWAYS);
        HUD_CORNER = b.comment("Esquina de la pantalla donde se dibuja la barra.")
                .defineEnum("corner", HudCorner.TOP_RIGHT);
        HUD_SCALE = b.comment("Tamano de la barra.")
                .defineInRange("scale", 1.0D, 0.5D, 2.0D);
        b.pop();

        SPEC = b.build();
    }

    private SpotifyConfig() {}
}
