package com.orochi.spotifybar.spotify;

/** Modelos inmutables de lo que devuelve la API de Spotify. */
public final class SpotifyModels {
    private SpotifyModels() {}

    public record Track(String uri, String name, String artists, String album, String imageUrl, long durationMs) {}

    public record Device(String id, String name, String type, boolean active, int volumePercent, boolean supportsVolume) {}

    public enum ItemKind { PLAYLIST, TRACK }

    /** Una fila de la lista de la pantalla: una playlist o una cancion. */
    public record Item(ItemKind kind, String uri, String name, String subtitle) {}

    public record Playback(Track track, boolean playing, long progressMs, boolean shuffle, String repeat,
                           Device device, String contextUri) {
        public static final Playback NONE = new Playback(null, false, 0L, false, "off", null, null);

        public Playback withPlaying(boolean playing, long progressMs) {
            return new Playback(track, playing, progressMs, shuffle, repeat, device, contextUri);
        }

        public Playback withProgress(long progressMs) {
            return new Playback(track, playing, progressMs, shuffle, repeat, device, contextUri);
        }

        public Playback withShuffle(boolean shuffle) {
            return new Playback(track, playing, progressMs, shuffle, repeat, device, contextUri);
        }

        public Playback withRepeat(String repeat) {
            return new Playback(track, playing, progressMs, shuffle, repeat, device, contextUri);
        }

        public Playback withVolume(int volumePercent) {
            if (device == null) return this;
            Device d = new Device(device.id(), device.name(), device.type(), device.active(), volumePercent,
                    device.supportsVolume());
            return new Playback(track, playing, progressMs, shuffle, repeat, d, contextUri);
        }
    }
}
