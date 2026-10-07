package com.orochi.spotifybar.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.orochi.spotifybar.SpotifyBarMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Descarga la portada del album y la expone como textura de Minecraft.
 *
 * <p>Spotify sirve JPEG, asi que se decodifica con ImageIO (sin tocar Graphics2D para no inicializar AWT)
 * y se copia pixel a pixel a un {@link NativeImage}.
 */
public final class CoverTexture {
    public static final ResourceLocation LOCATION = new ResourceLocation(SpotifyBarMod.MODID, "cover");

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ExecutorService LOADER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SpotifyBar-cover");
        t.setDaemon(true);
        return t;
    });

    private static volatile String requestedUrl;
    private static volatile String loadedUrl;
    private static volatile int size = 64;

    private CoverTexture() {}

    /** Lado (en pixeles) de la textura cargada; la portada es cuadrada. */
    public static int size() {
        return size;
    }

    /** Devuelve la textura si la portada de {@code url} ya esta cargada; si no, la pide y devuelve null. */
    public static ResourceLocation get(String url) {
        if (url == null) {
            return null;
        }
        if (url.equals(loadedUrl)) {
            return LOCATION;
        }
        if (!url.equals(requestedUrl)) {
            requestedUrl = url;
            LOADER.execute(() -> load(url));
        }
        return null;
    }

    private static void load(String url) {
        try {
            HttpResponse<byte[]> resp = HTTP.send(
                    HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                return;
            }
            BufferedImage src = ImageIO.read(new ByteArrayInputStream(resp.body()));
            if (src == null) {
                return;
            }
            int w = src.getWidth();
            int h = src.getHeight();
            NativeImage img = new NativeImage(w, h, false);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int argb = src.getRGB(x, y);
                    int a = (argb >>> 24) & 0xFF;
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    img.setPixelRGBA(x, y, (a << 24) | (b << 16) | (g << 8) | r); // NativeImage usa ABGR
                }
            }
            Minecraft.getInstance().execute(() -> {
                if (!url.equals(requestedUrl)) {
                    img.close(); // ya se pidio otra portada mas reciente
                    return;
                }
                // register() cierra la textura anterior que hubiera con el mismo nombre
                Minecraft.getInstance().getTextureManager().register(LOCATION, new DynamicTexture(img));
                size = w;
                loadedUrl = url;
            });
        } catch (IOException | RuntimeException e) {
            // sin portada: la UI dibuja un cuadro gris
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
