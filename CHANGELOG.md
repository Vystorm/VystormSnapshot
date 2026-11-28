## 1.22.0 — 2026-11-27

- Album menu: the selected photo has a new button **Send as postcard** and a field for an optional greeting. The button opens Vystorm Core's player picker (online, nearby, recent, search) on Core 0.71.0 or newer, sends the card to the player you pick and brings you back to the album with the same photo; with an older Core it asks for the name in a text window. It does what `/snapshot postcard PLAYER [greeting]` does, with the same limits and messages, and you no longer have to hold the photo.
- The selected photo also has a **Caption** field: what you type is saved for the whole photo, as with `/snapshot caption TEXT`, without holding a print. An empty field removes the caption.
- In the chest album (Spigot, Paper 1.20.6, screens off) press Q on a photo to send it as a postcard (player picker or name, then the greeting) and Ctrl+Q to edit its caption; the photo lore names both keys. Before, Q on a photo reprinted it like a left-click; with postcards switched off or without `vystormsnapshot.postcard` it still does.
- The postcard button shows for players with `vystormsnapshot.postcard` while `postcards.enabled` is on, the caption field while `captions.enabled` is on. All commands stay as they were. Still runs with Vystorm Core 0.66.0 or newer; `config.yml` and the data files are unchanged.

## 1.21.0 — 2026-11-22

- Rankings for boards: with Vystorm Core 0.67.0 or newer and Vystorm Boards 1.21.0 or newer, a board can show the players with the most photos (`snapshot:photos`) and with the most likes in the gallery (`snapshot:likes`), for example `{type: top, source: "snapshot:photos", rows: 10}` with a `text` element above it as the heading. Both rankings are counted from the album and the gallery the plugin already saves, so they are complete from the first start and list offline players too. They never show where a photo was taken.
- The same rankings work as placeholders in every text that shows Vystorm placeholders: `%vystorm_snapshot_top_photos_1_name%`, `%vystorm_snapshot_top_photos_1_value%`, `%vystorm_snapshot_rank_likes%` and more, listed in the README. `/vadmin placeholders rankings snapshot` shows them with their names in seven languages.
- New switch `rankings.enabled`, on by default and listed in `/vadmin features` in seven languages. Off, the rankings are removed at the next `/snapshot reload`. With `album.enabled` off there is no photo ranking, with `gallery.enabled` off no like ranking.
- The plugin still runs with Vystorm Core 0.66.0 or newer. On a Core older than 0.67.0 it offers no rankings and everything else works as before.
- The plugin can now fetch the block textures from Mojang itself, so you no longer have to build `textures.zip` by hand. It is off by default and needs two new keys in `config.yml`: `rendering.vanilla-textures.download: true` and `rendering.vanilla-textures.accept-download: true`. Setting the second one means you accept the Minecraft EULA (https://www.minecraft.net/eula). Nothing is downloaded unless both are true. The new keys are added to your existing `config.yml` with their comments.
- With both keys on, the plugin downloads the official client jar of your server's Minecraft version once (about 40 MB, over HTTPS from Mojang's hosts only, checked against Mojang's SHA-1), writes the block textures, block models and blockstates into `plugins/VystormSnapshot/textures.zip` (about 2 MB) and deletes the jar again. This runs in the background at start and on `/snapshot reload` when `textures.zip` is missing or the server's Minecraft version changed. The console shows the size, the progress and the result. Mojang's textures stay on your server.
- A `textures.zip` you made yourself is never overwritten. The plugin marks its own zip with `textures.source.json` and only replaces that one.
- If Mojang does not list your server's Minecraft version, nothing is downloaded and the console says so. `rendering.vanilla-textures.version` takes another listed version or `latest-release` (default `auto`, the server's version).
- A failed download names its reason in the console and is tried again after 30 seconds, 5 minutes and 30 minutes, then at the next start or `/snapshot reload`. `/snapshot status` shows the state of the download in all seven languages, and the hint about missing textures now names the two keys. The switch is listed in `/vadmin features` as "Block textures from Mojang".
- New `/snapshot status` for admins (permission `vystormsnapshot.admin`, all seven languages). It shows whether block textures are loaded and how many, or that `textures.zip` is missing and the exact path where it has to go, and whether player skins are on, how many are cached and the last failure with its reason.
- Missing block textures are no longer easy to overlook. The console now warns at every start while `textures.zip` is missing (before: one info line), and an admin who takes a photo while it is missing gets one hint per session with the exact path. The plugin still ships no Minecraft assets: copy a `textures.zip` from your own Minecraft client as described in the README.
- Player skins that fail to load are no longer dropped without a word. Before, one failed download (for example a timeout to textures.minecraft.net) left that player as a plain coloured figure in every photo until they rejoined, and nothing said why. Now the console names the reason once (the same kind of failure at most once an hour, never once per photo), and the plugin tries again after 30 seconds, then after 2, 10 and 30 minutes and then every hour.
- `/snapshot reload` now also asks again for the skins of all online players at once, so a fixed connection or a changed skin shows in the next photo.
- Skin downloads wait up to 5 seconds for a connection and for the answer (before: 3 seconds).

## 1.20.2 — 2026-11-21

- Fixed on Spigot: `/snapshot postcard <player>` failed with an internal error (NoSuchMethodError in the console) and sent nothing. It looked the player up with a Paper-only method that Spigot does not have. The lookup now goes through Vystorm Core, which works on Spigot too. Broken on Spigot since postcards came in with 1.16.0; Paper and Purpur were not affected.

## 1.20.1 — 2026-11-20

- Faster start on servers with many photos: the plugin no longer reads and decodes every PNG in `photos/` when the server starts. A photo is read the first time a player sees its map after a restart.
- Less memory: photos nobody looks at during an uptime are not held in memory, and after a photo is drawn on its map the plugin lets go of its own copy of the pixels. The map keeps the picture.
- Photos look the same as before. Nothing changes in `photos/` or `album.yml`, so you can go back to 1.20.0 at any time.

## 1.20.0 — 2026-11-20

- Fixed: a `config.yml` with a YAML mistake is no longer overwritten. The plugin leaves the file as it is, names the line and column of the mistake in the console and in the answer of `/snapshot reload`, and keeps the last working settings (at start: the default settings) until you fix it.
- `/vadmin features` shows every switch of the plugin with a readable name in the admin's language, for example "Player skins" and "Camera menu on the F key", instead of a name built from the config key such as "Rendering › Player skins".
- `/snapshot search` with `album.enabled` off stays in help and tab completion and answers "This needs “Album” (album.enabled), which is switched off on this server." Before, it disappeared and typing it gave "unknown subcommand".
- The camera menu shows the camera settings as a list on Vystorm Screens: "Film style", "Photo size in maps", "Frame around the photo" and "Self-timer" each stand on one row with the chosen value on the right and a one-line description below, in all seven languages. A click picks the next value, a right click the previous one. Before, every setting had its own tab. Players without screens see the menu as before.
- Fixed: on Vystorm Screens a click on a camera setting ("Photo size in maps", "Film style" ...) answered "You lack permission for this." The screen hides your hand while it is open, so the plugin now changes the camera you held when you opened the menu. "Add the photo in your main hand" in a collection uses the photo you held when you opened the screens, too.
- README: names the platforms the plugin was tested on (Paper and Purpur 26.2, Paper 1.20.6, Spigot 26.2).
- Requires Vystorm Core 0.66.0 or newer. Update Core before you update this plugin.

## 1.19.0 — 2026-11-18

- Requires Vystorm Core 0.65.0 or newer. Update Core before you update this plugin.
- Every feature can now be switched on and off in `config.yml`. All switches are on by default, so a server that updates keeps working as before; the new keys are added to your existing `config.yml` with their comments. Each switch is listed in Core's `/vadmin features` with a one-line description in seven languages and applies at once, without a restart (only `tutorial.enabled` needs one).
  - The whole plugin: `camera.enabled`. Off: the camera takes no photos and opens no menu, and every `/snapshot` command except `reload` is switched off. Photos already taken keep showing.
  - Taking photos: `film-cost.enabled` (off: photos are free), `saved-photos.enabled` (off: new photos are not written to disk and show only until the next restart), `big-photos.enabled`, `camera-menu.enabled`, `film-styles.enabled`, `photo-frames.enabled`, `self-timer.enabled`, `telephoto.enabled`, `block-textures.enabled`.
  - Keeping photos: `album.enabled`, `caption-search.enabled`, `captions.enabled`, `collections.enabled`.
  - Admin tools: `admin-photos.enabled` (`/snapshot capture` and `/snapshot give`).
  - Tutorial: `tutorial.enabled` (needs a restart).
  - Keys that already existed keep their names and are now listed as switches too: `camera.craftable`, `camera.menu-key`, `selfie`, `show-location`, `rendering.player-skins`, `gallery.prints`.
- A switched-off feature answers one shared message, "This feature is switched off on this server.", in all seven languages (new language key `feature-off`), its subcommands are hidden from help and tab completion, and its menus and buttons are closed. Photos, albums, captions, collections, the gallery, postcards and contests stay saved and work again when the switch is back on.
- Tab completion and help follow a switch at once, without registering the command again.
- Placeholders: the plugin registers its values with Vystorm Core under the namespace `snapshot`: photos in the album, photos and likes in the gallery and the gallery limit, collections, the running contest with theme, minutes left and entries, and the camera cooldown. Examples: `%vystorm_snapshot_photos%` (photos in the player's album) and `{snapshot:contest_theme}`. `/vadmin placeholders snapshot` lists them. A value of a switched-off feature is empty.
- Faster: a click in a chest or another block container no longer copies its block state to check whether it is the contest menu.
- The off messages of postcards and contests are now the shared message. README: postcards are in the command and permission tables, and the compatibility line says what the build checks.

## 1.18.0 — 2026-11-17

- Requires Vystorm Core 0.64.0 or newer. Update Core before you update this plugin.
- The order in which Vystorm plugins react to the same event is now set in one place, Vystorm Core (`event-order` in Core's `config.yml`).
- A damaged `postcards.yml` or `contest.yml` is now set aside instead of being overwritten by the next save.
- License: GPL-3.0 from this version. Earlier versions stay under the MIT License.

## 1.17.1 — 2026-11-16

- Tab completion for `/snapshot albums`: the first word now suggests the available actions (create, open, add, title, share, unshare, delete).

## 1.17.0 — 2026-11-15

- Rebuild camera settings, photo album/search, gallery, contest entries and picker, private collections, collection photos and sharing as Core Panels with selection, paging and confirmed print/removal actions.
- Keep all previous chest menus as fallbacks for older Core or unavailable Panels. No item-moving menus require a chest-only interface.
- Add panel labels in all seven languages and retain existing photo, printing and access rules.

## 1.16.0 — 2026-11-12

- Postcards: hold a photo and use `/snapshot postcard <player> [greeting]` to send a copy with a short greeting. Online players get it at once; offline players receive it on their next join, with a note in the Vystorm Core inbox. `/snapshot postcards` collects cards that did not fit into the inventory. Ten cards per player per day by default (`postcards.per-day`), also in the first-run setup; permission `vystormsnapshot.postcard`.

## 1.15.0 — 2026-11-11

- Photo contest: admins start one with `/snapshot contest start <hours> <theme>`. Players enter one of their gallery photos and vote once per photo (not for their own) in `/snapshot contest`. When time is up the most-voted photo wins (earlier entry on a tie); everyone is told, a Core news post goes out and the winner gets XP or your reward commands (`contest.*`). `/snapshot contest end` closes it early.

## 1.14.0 — 2026-11-11

- Short tutorial in the main menu (/vmenu → Tutorials) in all seven languages.
- /snapshot without arguments opens your album.

## 1.13.0 — 2026-11-10

- First-run setup: when the plugin is installed for the first time, admins answer a few questions or take the recommended settings, with a preview before anything is saved (needs Vystorm Core 0.54.0 or newer).
- All settings, including extra configuration files, appear in Core's new `/vadmin config` menu.

## 1.12.0 - Private photo collections

Create named collections with `/snapshot albums create NAME`, add one of your own held photographs with `/snapshot albums add NAME`, and browse them through `/snapshot albums` or the Core menu. A multi-map photograph occupies one album entry; printing returns the whole photograph using the existing film/paper rules. The original `/snapshot album` library remains available.

Collections start private. Owners can edit the title, explicitly share with a known player, revoke that access, remove individual photos or delete the collection. Every viewing and printing action rechecks access, including menus opened before revocation. Revoking access does not remove previously printed items. Photos themselves are retained when deleting a collection.

Data is stored atomically in `photos/collections.yml`. Limits: 20 collections per owner, 200 photographs per collection and 32 readers. A failed save leaves existing access unchanged; malformed data is retained and editing disabled. All new messages support English, German, Simplified Chinese, French, Brazilian Portuguese, Italian and Russian.

## 1.11.0 - Spigot and CraftBukkit compatibility

- Require Vystorm Core 0.51.0 or newer.
- Use portable spyglass detection and live player profiles, retaining the existing rendering and photo data.
- Validation: 83 automated checks (0 skipped), plus combined startup, listener registration and Core diagnostics/help on official Spigot26.3 and CraftBukkit26.3.

## 1.10.0 (2026-11-06)

- Add complete French (`fr`), Brazilian Portuguese (`pt_br`), Italian (`it`) and Russian (`ru`) language bundles. Minecraft client locales and the Core language selector choose them automatically.
- Validate packaged language keys, placeholders, formatting and tutorial coverage in server-loader tests.

## 1.8.0 (2026-11-05)

- Add `/snapshot search TEXT` to find your own photos by caption, with literal case-insensitive matching, paging and the existing reprint/gallery actions. Includes English, German and Simplified Chinese commands and titles.
- Preserve malformed album records when adding photos. A malformed `photos` root stays unchanged and disables saving until restart. Invalid entries remain unavailable and must be repaired; new entries cannot overwrite their map IDs.

## 1.7.0 (2026-11-03)

- Runs on Paper 1.20.6 – 26.x. Java 21 bytecode; plugin API minimum is now 1.20.6. Use the Java runtime required by your Paper server.
- Requires Vystorm Core 0.48.0+: camera, album and gallery menus use Core's feature-detected inventory compatibility adapter for the class/interface change in InventoryView.
- No Snapshot features disabled on older servers. The flash falls back to an uncoloured particle; unavailable camera materials fall back to the spyglass and unavailable film materials to paper. Mobs and blocks added later are only available on newer servers.
- Offline `compat1206` compilation and compatibility tests with both API generations are part of `check`.

## 1.6.0 (2026-11-03)

- **Photo captions**: hold one of your photos and use `/snapshot caption [text]` to set or clear a persistent caption. Any tile updates the whole big photo. Captions appear in the album, gallery and future reprints, and immediately on the held print. Only the photographer can edit them; text stays plain, with Unicode support and a configurable character limit (`captions.max-length`, default 80, added to existing configs). Stored atomically in `photos/album.yml`; failed saves leave the old caption intact. Uses `vystormsnapshot.use`, with English, German and Simplified Chinese commands and messages.

## 1.5.0 (2026-11-02)

- **Album world filter**: `/snapshot album [world]` shows only your photos from that world, newest first. Tab completion suggests worlds from your own saved photos, including unloaded worlds. The filter stays active when changing pages or publishing a photo; omit the world to see the full album. Uses the existing `vystormsnapshot.use` permission, no new configuration.

## 1.4.0 (unreleased)

- **Self-timer**: pick 3 or 10 seconds in the camera menu (swap-hand key with the camera, or `/snapshot camera`), click, and step into the picture. The camera stays where you clicked, counts down with ticks and an action-bar countdown, then takes the photo from there – with you in it. Film and cooldown work as for a normal photo; the setting stays on the camera and shows in its lore. Config `timers` (default `[0, 3, 10]`, at most 30 seconds; `[0]` hides the choice).

## 1.3.0 (unreleased)

Needs Vystorm Core 0.45.0.

- Simplified Chinese (zh_cn) translation.
- `/snapshot` now runs through Vystorm Core's command system: subcommands, help, tab completion and error messages in the player's language (`/snapshot galerie`, `/snapshot kamera`, `/snapshot 相册` work next to the English names for everyone), a clickable `/snapshot help` (`hilfe`, `帮助`), a "did you mean" for typos and an entry in `/vhelp`. New localized root commands `/schnappschuss` (German) and `/相机` (Chinese) from the language files (`commands.snapshot.*`); which languages are active is set server-wide in Core (`commands.localized-aliases`). `/snapshot` and every English subcommand stay, also from the console. The command is no longer declared in plugin.yml.

## 1.2.0 (2026-10-30)

- Camera menu with film styles and frames: press the swap-hand key (F) with the camera in hand (or `/snapshot camera`) to pick a film style (color, black & white, sepia, vintage, noir, vivid), the photo format and a frame (none, white instant-photo frame, black) with one click, plus shortcuts to the album and the gallery. The settings stay on the camera and show in its lore. Config: `camera.menu-key`, `styles`, `frames`.
- Photo gallery: right-click a photo in the album to put it on show (big photos count as one). `/snapshot gallery` (also in `/vmenu` → Community) shows the photos of all players, newest or most liked first; click to like (the photographer gets a notice), shift-click for a print, shift-right-click to take your own photo down (moderators: any photo). With Vystorm Core 0.43.0 the player menu (sneak + right-click a player) gets a "Photos by …" entry; `/snapshot gallery <player>` does the same. Stored in `photos/gallery.yml`. Config: `gallery.enabled`, `gallery.prints`, `gallery.max-per-player`; permissions `vystormsnapshot.gallery` (everyone) and `vystormsnapshot.gallery.moderate` (op).
- The album and the gallery are also in the Vystorm Core main menu (`/vmenu` → Community).

### Fixed

- Album hint now says "Left-click to reprint": with the gallery on, right-click puts a photo on show instead of reprinting it.
- An unreadable `photos/gallery.yml` (for example after a hand edit with a YAML error) loaded as an empty gallery and was then overwritten by the next like or publish, so all exhibits and likes were lost. The file is now moved aside as `gallery.yml.broken-<date-time>`, the console explains how to restore it and the gallery stays off (read-only, players get a notice, moderators the file name) until the next restart.
- The same for `photos/album.yml`: an unreadable album loaded as empty and the next photo overwrote it, so all players lost their album (and with it the photo credits). The file is now moved aside as `album.yml.broken-<date-time>`, the console explains how to restore it and the album is not saved again until the next restart.
- Every like click rewrote the whole `gallery.yml`. Changes are now collected and written atomically at most every 10 seconds and on shutdown.

## 1.1.0 (2026-10-29)

- Album pages: players with more than 54 photos now see all of them; the album switches to pages of 45 with arrows in the bottom row (before, only the newest 54 were shown).
- Reprint whole big photos: shift-click any part of a 2x1, 2x2 … photo in the album to reprint all of its maps at once (one paper per map); a normal click still reprints just that map.

## 1.0.1 (2026-10-28)

- Registers itself for the Vystorm Core update check (Core 0.40.2+); older Cores are unaffected.

# Changelog

## 1.0.0 (2026-10-28)

- First release: craftable camera, photos as maps rendered over ticks with face shading, block and sky light, day/night sky, distance haze, see-through glass, mobs and players; PNG storage restored after restarts; film cost, cooldown, parallel limit; `/snapshot give|capture|reload`; English and German messages.
- Built on Vystorm Core: Core language system (`lang/en.yml`, `lang/de.yml`), in-game config editor (`/vadmin config`), Core task groups and atomic file writes.
- Photo formats over several maps (`/snapshot format`), photo album with reprints (`/snapshot album`), selfie when sneaking, telephoto through the spyglass, flash in the dark, location in the lore, `capture` with a format.
