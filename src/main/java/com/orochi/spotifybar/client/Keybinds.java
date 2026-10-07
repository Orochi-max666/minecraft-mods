package com.orochi.spotifybar.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class Keybinds {
    public static final String CATEGORY = "key.categories.spotifybar";

    public static final KeyMapping OPEN = new KeyMapping("key.spotifybar.open", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, CATEGORY);
    public static final KeyMapping PLAY_PAUSE = new KeyMapping("key.spotifybar.play_pause", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_BACKSLASH, CATEGORY);
    public static final KeyMapping NEXT = new KeyMapping("key.spotifybar.next", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_BRACKET, CATEGORY);
    public static final KeyMapping PREVIOUS = new KeyMapping("key.spotifybar.previous", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT_BRACKET, CATEGORY);

    private Keybinds() {}
}
