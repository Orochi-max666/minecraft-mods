# minecraft-mods
Repository for Minecraft modifications and mods development

## Spotify Bar (Forge 1.20.1)

Mod **solo de cliente** que añade una barra para ver, elegir y cambiar la música de Spotify sin salir de Minecraft.

> **Cómo funciona:** el mod *controla* tu Spotify (app de PC, móvil, web...) mediante la Web API oficial. El audio
> lo reproduce Spotify, no Minecraft. Para controlar la reproducción Spotify exige **cuenta Premium**.

### Qué incluye
- **Barra en pantalla (HUD)**: portada, título, artista y progreso. Esquina, tamaño y modo configurables.
- **Pantalla de control** (tecla `K`): anterior / play-pausa / siguiente, aleatorio, repetir, volumen, barra de
  progreso para saltar a cualquier punto, **tus playlists** y **buscador** de canciones y playlists. Clic para reproducir.
- **Tira en el inventario**: al abrir el inventario, un cofre, la mesa de crafteo, etc. aparece una tira ancha
  (encima de la ventana, o debajo si no cabe) con portada, título, progreso, volumen, anterior / play / siguiente,
  aleatorio, repetir y un desplegable **Playlists** para cambiar de música mientras ordenas tus objetos.
  Se puede desactivar con `[inventory] enabled = false`.
- **Atajos**: `\` play/pausa, `]` siguiente, `[` anterior (todas reasignables en Controles → *Spotify Bar*).
- Si no hay dispositivo activo, usa tu PC (o el primer dispositivo disponible) automáticamente.

### Puesta en marcha (una sola vez)
1. Entra en <https://developer.spotify.com/dashboard> y crea una app.
2. En *Redirect URIs* añade exactamente: `http://127.0.0.1:8888/callback`
   (si cambias `redirectPort` en la config, cambia también aquí el puerto).
3. Copia el **Client ID** de la app.
4. En el juego pulsa `K`, pega el Client ID y pulsa **Conectar**. Se abre el navegador para autorizar.

El login usa OAuth con PKCE: **no se necesita ni se guarda ningún client secret**. El token de sesión se guarda en
`~/.spotifybar/tokens.json` (fuera de la carpeta del juego). No lo compartas.

### Configuración
`config/spotifybar-client.toml`: `clientId`, `redirectPort`, `pollSeconds`, en `[hud]` `mode`
(`ALWAYS` / `ON_TRACK_CHANGE` / `OFF`), `corner` y `scale`, y en `[inventory]` `enabled`.

### Compilar
Requiere **Java 17**.

```
./gradlew build
```

El `.jar` queda en `build/libs/`. GitHub Actions también lo compila en cada push (artefacto `spotifybar-jar`).

### Estructura
- `spotify/` — login PKCE, cliente de la API y servicio con sondeo. No depende de Minecraft.
- `client/` — HUD, pantalla, teclas y textura de portada (Forge/Minecraft).
- `config/` — configuración de cliente.
