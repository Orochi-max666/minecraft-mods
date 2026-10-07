package com.orochi.spotifybar;

import com.orochi.spotifybar.config.SpotifyConfig;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;

/** Mod solo de cliente. Todo el comportamiento vive en el paquete {@code client}. */
@Mod(SpotifyBarMod.MODID)
public final class SpotifyBarMod {
    public static final String MODID = "spotifybar";

    public SpotifyBarMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, SpotifyConfig.SPEC);
    }
}
