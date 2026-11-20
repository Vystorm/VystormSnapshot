# Vystorm Snapshot

**A camera for your players.** Craft a camera, aim and click: a moment later the photo arrives as a map you can hang in an item frame, trade or put in a book of memories. Day and night, light from torches, distance haze, water, mobs and players are all in the picture.

- Camera recipe: iron ingots around a glass pane, redstone below. Default item is a spyglass, so right-click zooms like a viewfinder and left-click takes the photo (configurable).
- Each photo uses one paper (configurable item or free; creative is always free).
- Photos render a few rows per tick, so taking one never lags the server; parallel photos are limited.
- Every photo is saved as a 128x128 PNG in `plugins/VystormSnapshot/photos/<map id>.png` and restored after restarts.
- Big photos: `/snapshot format 2x1` (also 2x2, 3x2 â¦) turns one shot into several maps that form one picture on a wall.
- `/snapshot album`: all your photos as a menu with pages, click one to reprint it, shift-click a part of a big photo to reprint the whole picture.
- Camera menu (sneak + right-click, or swap-hand key F): film styles like black & white, sepia, vintage and noir, photo frames and the format with one click. Sneak + right-click always opens settings, even with a right-click shutter.
- Photo gallery: put your best photos on show, browse everyone's photos newest or most liked first, like them and take prints.
- Look through the spyglass for a telephoto shot, sneak for a selfie, and in the dark the flash lights up the scene.
- Self-timer (3 or 10 seconds, camera menu): the camera stays where you clicked, counts down and takes the photo with you in it.
- Photos remember where they were taken (coordinates and compass direction in the lore).
- `/snapshot capture <world> <x> <y> <z> <yaw> <pitch>`: admins and the console take photos from any position (spawn boards, news, events).
- Texts in seven languages (`lang/en.yml`, `de`, `fr`, `it`, `pt_br`, `ru` and `zh_cn`) through the Vystorm Core language system (player language, `/vadmin language audit`); `config.yml` is editable in game with `/vadmin config`.
- Needs [Vystorm Core](https://www.spigotmc.org/resources/139848/) 0.66.0 or newer (free). What the build checks: Java 21 bytecode (`--release 21`), compiled against the Paper API 1.21.11 and, in `compat1206` and `compat1206Test`, against Paper 1.20.6. Use the Java version your server needs.
- Tested on Paper and Purpur 26.2, Paper 1.20.6 and Spigot 26.2.

## Photo contest (1.15.0)

Admins start a themed contest (`/snapshot contest start <hours> <theme>`); players enter a gallery photo and vote in `/snapshot contest`. The winner is announced and rewarded.

## Camera menu: film styles and frames

**Sneak + right-click** with the camera in your main hand, press the **swap-hand key (F)**, or type `/snapshot camera` and set up the camera with one click:

- **Film style:** Color, Black & white, Sepia, Vintage (warm, faded, dark corners), Noir (hard contrast) or Vivid (strong colours).
- **Format:** every photo format the server allows (1x1, 2x1, 2x2, 3x2 …), no need to type `/snapshot format`.
- **Frame:** none, a white instant-photo frame (wider at the bottom) or a thin black frame. On big photos the frame runs around the whole picture.
- Shortcuts to your album and to the gallery.

The settings stay on the camera and show in its lore, so every camera can have its own look.

## Album world filter

Use `/snapshot album world_nether` to find your Nether photos, or choose a world with tab completion. Suggestions come from your own saved photos, including worlds that are no longer loaded. World matching ignores case. The filtered album keeps newest-first ordering, page navigation, reprints and gallery publishing; its title shows the world and matching map count. An unknown world opens an empty album. Use `/snapshot album` to see everything again. No new configuration or permissions are needed.

## Find photos by caption

`/snapshot search TEXT` finds your own saved photos by caption, newest first. Matching is literal and ignores letter case; phrases, Chinese text and emoji work. Page navigation and publishing keep the search active, and reprinting a large photo still prints its complete set. Other players' private photos are never included. `/snapshot album` returns to the unfiltered album. German: `suchen`; Chinese: `搜索`. Uses the existing `vystormsnapshot.use` permission.

## Block textures and player skins (1.9.0)

Camera photos can use Minecraft's actual block textures instead of flat map colours. Put your own vanilla client JAR or resource-pack ZIP at `plugins/VystormSnapshot/textures.zip`, then run `/snapshot reload`. The archive must contain `assets/minecraft/textures/block`, `models/block` and optionally `blockstates`. Set `rendering.resource-pack` for another path. Asset loading happens in the background; a missing pack falls back to map colours, and a failed reload retains the previous textures. No Minecraft assets are included in this plugin's JAR or Git repository.

The renderer samples texture pixels directly, follows model texture inheritance and block-state rotations, and composites tinted layers. Animated textures use their first frame. Multipart models, biome-exact tinting, custom namespaces and client-only displays/shaders are not reproduced. Geometry still follows the server's block collision shapes; decorative model geometry can differ from the client.

With `rendering.player-skins: true` (default), official profile skins are fetched asynchronously and mapped onto a six-part player figure, including skin overlays and slim arms. Pose, armour, capes and held items are not reproduced. A skin that is still loading or unavailable keeps the existing colour fallback. Invisible players and players hidden from the photographer are excluded. Downloads and decoded image sizes are bounded; shutdown closes the loader.

One Minecraft map is always 128×128 pixels. For more detail, choose `2x2` (256×256) or `3x2` (384×256) in the camera menu; administrators can also enable `4x3` or `4x4` in `formats`. Textures improve the content of new photos; they do not change old prints or turn a single map into a high-resolution display. A photo uses one paper per map. With the default left-click shutter, sneak + left-click still takes a selfie; sneak + right-click is reserved for settings.

## Photo captions

Hold one of your photo maps and use `/snapshot caption Our first castle` to attach a permanent caption to the photo in the album. Only its photographer may edit it. Any tile of a big photo updates the whole picture. `/snapshot caption` without text removes the caption.

Captions are stored in `photos/album.yml` and appear in the album, gallery and future reprints. The held print is updated immediately; other existing prints and already open menus keep their old lore until reprinted or reopened. Text is plain text, including Chinese and emoji; formatting codes and control characters are rejected. The default limit is 80 Unicode characters, configurable with `captions.max-length` (1–200). Existing configurations receive this setting automatically; `/snapshot reload` applies changes. Uses `vystormsnapshot.use`.

## Photo gallery

Show your best photos to the whole server:

- In the album, **right-click** a photo to put it on show in the gallery (right-click again takes it down). A big photo counts as one exhibit.
- `/snapshot gallery` (or `/vmenu` → Community → Photo gallery) shows the photos of all players, **newest** or **most liked** first.
- **Click** a photo to like it, the photographer gets a notice. **Shift-click** for a print (one paper per map, like a reprint). **Shift-right-click** takes your own photo down; moderators can take down any photo.
- Sneak + right-click a player (Vystorm Core player menu) → **Photos by …** opens that player's photos; `/snapshot gallery <player>` does the same.
- Each player may show up to 20 photos at once (configurable). Stored in `plugins/VystormSnapshot/photos/gallery.yml`. Changes are saved every 10 seconds and on shutdown; if the file cannot be read it is kept as `gallery.yml.broken-<date-time>` and the gallery stays off until you fix it and restart.

## Commands

| | |
|---|---|
| `/snapshot album [world]` | your photos (optionally from one world): click to reprint, shift-click to reprint a whole big photo, right-click to show it in the gallery |
| `/snapshot camera` | camera menu (same as the swap-hand key with the camera in hand) |
| `/snapshot caption [text]` | caption your held photo (all tiles); omit text to clear it |
| `/snapshot gallery [player]` | the public photo gallery, or the photos of one player |
| `/snapshot format WxH` | photo format of the camera in hand |
| `/snapshot postcard PLAYER [greeting]` | send a copy of the held photo with a short greeting, also to offline players |
| `/snapshot postcards` | take postcards that did not fit into your inventory |
| `/snapshot give [player]`, `capture WORLD X Y Z YAW PITCH [WxH]`, `reload` | admin tools |

## Permissions

| | |
|---|---|
| `vystormsnapshot.use` | take photos, album, camera menu, caption own photos (everyone) |
| `vystormsnapshot.gallery` | browse the gallery, like photos, show own photos (everyone) |
| `vystormsnapshot.gallery.moderate` | take any photo down from the gallery (op) |
| `vystormsnapshot.postcard` | send and collect postcards |
| `vystormsnapshot.admin` | `/snapshot give`, `capture`, `reload` (op) |

## Switches

Every feature has an on/off switch in `config.yml` (all on by default, listed in Vystorm Core's `/vadmin features`, applied without a restart except `tutorial.enabled`): `camera.enabled` (the whole plugin), `camera.craftable`, `film-cost.enabled`, `saved-photos.enabled`, `big-photos.enabled`, `camera-menu.enabled`, `camera.menu-key`, `film-styles.enabled`, `photo-frames.enabled`, `self-timer.enabled`, `telephoto.enabled`, `selfie`, `flash.enabled`, `show-location`, `block-textures.enabled`, `rendering.player-skins`, `album.enabled`, `caption-search.enabled`, `captions.enabled`, `collections.enabled`, `gallery.enabled`, `gallery.prints`, `postcards.enabled`, `contest.enabled`, `admin-photos.enabled`, `tutorial.enabled`. A switched-off feature answers "This feature is switched off on this server."; `/snapshot search` with `album.enabled` off names that switch; photos, albums, captions, collections, the gallery, postcards and contests stay saved.

## Placeholders

With Vystorm Core 0.65.0 the plugin registers the namespace `snapshot` (server thread; empty while the feature is switched off): `photos`, `gallery_shown`, `gallery_max`, `gallery_likes`, `collections`, `contest_running`, `contest_theme`, `contest_ends_minutes`, `contest_entries`, `camera_cooldown`. Examples: `%vystorm_snapshot_photos%` (photos in the player's album) and `{snapshot:contest_theme}`. `/vadmin placeholders snapshot` lists them.

## Configuration

```yaml
camera:
  menu-key: true            # F with the camera in hand opens the camera menu
styles: [color, black-white, sepia, vintage, noir, vivid]
frames: [none, white, black]
captions:
  max-length: 80           # caption limit in Unicode characters, clamped to 1–200
gallery:
  enabled: true
  prints: true              # shift-click in the gallery gives a print
  max-per-player: 20        # 0 = no limit
```

## Building

`./gradlew build` → `build/libs/VystormSnapshot-<version>.jar`


## Compatibility and verification

All Snapshot features remain available on Paper 1.20.6, including the chest menus, photos, captions and gallery. The flash uses the uncoloured particle on older servers. Materials configured as the camera that do not exist on the server fall back to the spyglass, and unavailable film materials to paper (only `none` makes photos free); newer mobs and blocks naturally appear only on servers that provide them. Core's inventory adapter detects the runtime API shape without parsing server versions.

Build with JDK 25 and `-PvystormCoreJar=<path-to-Core-0.66.0-or-newer.jar>`; all Java sources target release 21. `check` includes `compat1206` (compiles main sources against only the old API and its libraries plus Core) and `compat1206Test` (runs the compatibility tests against those libraries using the shipped main classes). Set `-Ppaper1206Libraries=<libraries-directory>` to override the default `<home>/source/tools/smoke-1206/libraries`. These checks use local jars and work offline once the normal build dependencies are cached.

## Private photo collections

Create named collections with `/snapshot albums create NAME`, add one of your own held photographs with `/snapshot albums add NAME`, and browse them through `/snapshot albums` or the Core menu. A multi-map photograph occupies one album entry; printing returns the whole photograph using the existing film/paper rules. The original `/snapshot album` library remains available.

Collections start private. Owners can edit the title, explicitly share with a known player, revoke that access, remove individual photos or delete the collection. Every viewing and printing action rechecks access, including menus opened before revocation. Revoking access does not remove previously printed items. Photos themselves are retained when deleting a collection.

Data is stored atomically in `photos/collections.yml`. Limits: 20 collections per owner, 200 photographs per collection and 32 readers. A failed save leaves existing access unchanged; malformed data is retained and editing disabled. All new messages support English, German, Simplified Chinese, French, Brazilian Portuguese, Italian and Russian.

## License

Copyright (c) 2026 Esoren / Vystorm

Versions after 1.17.1 are licensed under the GNU General Public License v3.0 (GPL-3.0), see [LICENSE](LICENSE).
Version 1.17.1 and earlier were released under the MIT License ([LICENSE-MIT-until-1.17.1.txt](LICENSE-MIT-until-1.17.1.txt)) and stay available under those terms.
