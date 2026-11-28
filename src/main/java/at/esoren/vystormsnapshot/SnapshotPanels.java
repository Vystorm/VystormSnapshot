package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import static at.esoren.vystormsnapshot.SnapshotPanel.*;

/** Server-thread adapter: only server-owned requests, current data and checked service calls. */
final class SnapshotPanels {
    static final List<String> NAMES = List.of("camera", "album", "gallery", "contest", "contest-picker", "collections", "collection", "sharing");
    private final SnapshotPlugin plugin;
    private final API core;
    private final Lang lang;
    private final Album album;
    private final Gallery gallery;
    private final PhotoContest contest;
    private final CollectionsMenu collections;
    private final Panels panels;
    private final Map<UUID, Map<String, State>> states = new HashMap<>();
    /** Selection, page, search and sort are independent for each player and screen. */
    static final class State {
        int page;
        String selected, world, query, targetName;
        UUID target;
        boolean top;
        /** The greeting typed for "Send as postcard" in the album (1.22.0); kept until a card is sent. */
        String greeting = "";
        /**
         * The hotbar slot the player held when this screen opened, -1 if unknown. A Core screen selects an empty slot
         * while it is open (it hides the first-person hand), so clicks must not read the main hand.
         */
        int slot = -1;
    }

    SnapshotPanels(SnapshotPlugin plugin, API core, Lang lang, Album album, Gallery gallery, PhotoContest contest, CollectionsMenu collections) {
        this.plugin = plugin; this.lang = lang; this.album = album; this.gallery = gallery;
        this.contest = contest; this.collections = collections; this.core = core; this.panels = core.gui().panels().of(plugin);
    }

    void register() {
        for (String name : NAMES) panels.register(name, PanelSpec.builder(lang.ref(titleKey(name)))
                .access(p -> allowed(p, name)).render(ctx -> render(ctx, name))
                .fallback(p -> fallback(p, name, state(p, name))).build());
    }

    boolean open(Player p, String name, int page, String world, String query, UUID target, String targetName, boolean top) {
        State s = new State(); s.page = Math.max(0, page); s.world = world; s.query = query;
        s.target = target; s.targetName = targetName; s.top = top;
        Map<String, State> mine = states.computeIfAbsent(p.getUniqueId(), id -> new HashMap<>());
        s.slot = openSlot(p, mine.values());
        mine.put(name, s);
        if (!allowed(p, name)) return false;
        return panels.open(p, name) != PanelOpenResult.NONE;
    }

    void forget(UUID player) { states.remove(player); }
    private State state(Player p, String name) { return states.computeIfAbsent(p.getUniqueId(), id -> new HashMap<>()).computeIfAbsent(name, id -> new State()); }
    /**
     * The slot the player really holds. Opened from the world that is the selected slot. Opened from another Core
     * screen (the hand is hidden there: an empty selected slot and an invisible player) it is the slot that screen was
     * opened with.
     */
    static int openSlot(Player p, Collection<State> earlier) {
        PlayerInventory inventory = p.getInventory();
        if (inventory == null) return -1;
        int selected = inventory.getHeldItemSlot();
        ItemStack item = inventory.getItem(selected);
        if ((item == null || item.getType() == Material.AIR) && p.isInvisible())
            for (State before : earlier) if (before.slot >= 0 && before.slot != selected) return before.slot;
        return selected;
    }

    /** The item in the slot the screen was opened with (the main hand if unknown). */
    static ItemStack held(Player p, State s) {
        PlayerInventory inventory = p.getInventory();
        if (inventory == null) return null;
        return s.slot >= 0 && s.slot < 9 ? inventory.getItem(s.slot) : inventory.getItemInMainHand();
    }

    private boolean allowed(Player p, String name) {
        if (!plugin.isEnabled() || !p.isOnline() || !p.hasPermission("vystormsnapshot.use")) return false;
        State s = state(p, name);
        if (!SnapshotPlugin.enabled(plugin, Feature.CAMERA)) return false;
        if (name.startsWith("collection") || name.equals("sharing")) { if (!SnapshotPlugin.enabled(plugin, Feature.COLLECTIONS)) return false; }
        return switch (name) {
            case "camera" -> SnapshotPlugin.enabled(plugin, Feature.CAMERA_MENU) && plugin.isCamera(held(p, s));
            case "album" -> SnapshotPlugin.enabled(plugin, Feature.ALBUM);
            case "gallery" -> plugin.galleryOn(p);
            case "contest", "contest-picker" -> plugin.contestEnabled() && contest.running(System.currentTimeMillis());
            case "collection", "sharing" -> {
                var c = collections.data.get(s.target);
                yield c != null && (name.equals("sharing") ? c.owner().equals(p.getUniqueId()) : c.visible(p.getUniqueId()));
            }
            default -> true;
        };
    }

    private void fallback(Player p, String name, State s) {
        switch (name) {
            case "camera" -> plugin.openCameraChest(p);
            case "album" -> plugin.openAlbumChest(p, s.page, s.world, s.query);
            case "gallery" -> plugin.openGalleryChest(p, s.page, s.top, s.target, s.targetName);
            case "contest" -> plugin.openContestChest(p);
            case "contest-picker" -> plugin.openContestPickerChest(p);
            case "collections" -> collections.listChest(p, s.page);
            case "collection" -> collections.openChest(p, s.target, s.page);
            case "sharing" -> collections.sharingChest(p, s.target);
            default -> throw new IllegalArgumentException(name);
        }
    }

    private static String titleKey(String name) {
        return switch (name) {
            case "camera" -> "camera-menu-title";
            case "album" -> "menu-album";
            case "gallery" -> "menu-gallery";
            case "contest" -> "commands.snapshot.sub.contest.description";
            case "contest-picker" -> "contest.picker-title";
            case "sharing" -> "collections.sharing";
            default -> "collections.title";
        };
    }

    private Panel render(PanelContext ctx, String name) {
        if (!allowed(ctx.player(), name)) return Panel.page(ctx.text(titleKey(name))).child(Ui.text(ctx.text("denied")));
        return SnapshotPanel.render(ctx, view(ctx, name, state(ctx.player(), name)), actions(name));
    }

    /** The screen's handlers; buttons in {@link SnapshotPanel#HAND_OVER} get the panel action. */
    Actions actions(String name) {
        return new Actions() {
            @Override public Result run(Player p, String action, String value) { return SnapshotPanels.this.run(p, name, action, value); }
            @Override public Result run(PanelAction a, String action, String value) {
                return action.equals("postcard") && name.equals("album") ? postcard(a, value) : run(a.player(), action, value);
            }
        };
    }

    /**
     * "Send as postcard" in the album (1.22.0): Core's player picker sends the card with the greeting typed above and
     * comes back to the album; without the picker the name is asked in a text prompt and the album opens again.
     */
    Result postcard(PanelAction a, String value) {
        Player p = a.player();
        if (!allowed(p, "album") || !plugin.postcardsOn(p)) return Result.error(lang.plain(p, "denied"));
        int map;
        try { map = Integer.parseInt(value); } catch (NumberFormatException invalid) { return Result.error(lang.plain(p, "denied")); }
        var photo = album.entry(map);
        if (photo == null || !photo.photographer().equals(p.getUniqueId())) return Result.error(lang.plain(p, "denied"));
        State s = state(p, "album");
        String greeting = s.greeting == null ? "" : s.greeting;
        if (plugin.playerPicker()) {
            try { return SnapshotPicks.postcard(core, plugin, lang, a, map, greeting, () -> s.greeting = ""); }
            catch (LinkageError olderCore) { plugin.getLogger().fine("Player picker not available: " + olderCore); }
        }
        a.closeAndThen(next -> plugin.postcardByName(next, map, greeting, () -> {
            if (next.isOnline()) panels.open(next, "album");
        }));
        return Result.ok();
    }

    View view(PanelContext ctx, String name, State s) {
        Player p = ctx.player(); UUID viewer = p.getUniqueId();
        String title = ctx.text(titleKey(name)), subtitle = ctx.text("panel.browse");
        List<Row> rows = new ArrayList<>(); List<Button> footer = new ArrayList<>(); List<Choice> choices = new ArrayList<>();
        Material icon = Material.BOOK;
        switch (name) {
            case "camera" -> {
                icon = Material.SPYGLASS; subtitle = ctx.text("commands.snapshot.sub.camera.description");
                var camera = held(p, s);
                choices.add(new Choice("style", ctx.text("panel.style"), plugin.styleOf(camera).id,
                        plugin.styles().stream().map(v -> new Option(v.id, ctx.text("style-" + v.id))).toList(), ctx.text("panel.style-desc")));
                choices.add(new Choice("format", ctx.text("panel.format-name"), plugin.formatOf(camera),
                        plugin.formats().stream().map(v -> new Option(v, v)).toList(), ctx.text("panel.format-desc")));
                choices.add(new Choice("frame", ctx.text("panel.frame-name"), plugin.frameOf(camera).id,
                        plugin.frames().stream().map(v -> new Option(v.id, ctx.text("frame-" + v.id))).toList(), ctx.text("panel.frame-desc")));
                choices.add(new Choice("timer", ctx.text("panel.timer"), Integer.toString(plugin.timerOf(camera)),
                        plugin.timers().stream().map(v -> new Option(v.toString(), v == 0 ? ctx.text("camera-menu-timer-off") : ctx.text("camera-menu-timer", "seconds", v))).toList(), ctx.text("panel.timer-desc")));
            }
            case "album" -> {
                subtitle = s.world == null ? ctx.text("menu-album-desc") : s.world;
                for (var photo : album.of(viewer, s.world, s.query)) {
                    List<Button> buttons = new ArrayList<>();
                    buttons.add(button(ctx, "print", "panel.print", Material.PAPER, photo.map(), false, true, false));
                    if (album.set(photo.map()).size() > 1) buttons.add(button(ctx, "print-all", "panel.print-all", Material.MAP, photo.map(), false, true, false));
                    if (plugin.galleryOn(p)) {
                        boolean shown = gallery.shown(album.set(photo.map()).getFirst());
                        buttons.add(button(ctx, "publish", shown ? "panel.unpublish" : "panel.publish", Material.PAINTING, photo.map(), shown, shown, false));
                    }
                    // 1.22.0: caption and postcard without holding the print or typing the command
                    List<Field> fields = new ArrayList<>();
                    if (plugin.captionsOn()) fields.add(new Field("caption", ctx.text("panel.caption"), photo.caption(),
                            Math.clamp(plugin.captionMax(), 1, 200), ctx.text("panel.caption-hint"), "caption:" + photo.map()));
                    if (plugin.postcardsOn(p)) {
                        fields.add(new Field("greeting", ctx.text("panel.postcard-text"), s.greeting, Postcards.MAX_TEXT,
                                ctx.text("panel.postcard-text-hint"), "greeting"));
                        buttons.add(button(ctx, "postcard", "panel.postcard", Material.WRITABLE_BOOK, photo.map(), false, false, false));
                    }
                    rows.add(withFields(photo(ctx, photo, buttons, ""), fields));
                }
            }
            case "gallery" -> {
                icon = Material.PAINTING;
                subtitle = s.target == null ? ctx.text("menu-gallery-desc") : ctx.text("menu-photos", "player", s.targetName == null ? s.target.toString() : s.targetName);
                for (var exhibit : gallery.order(s.top, s.target)) {
                    var photo = album.entry(exhibit.map); if (photo == null) continue;
                    List<Button> buttons = new ArrayList<>();
                    if (!exhibit.photographer.equals(viewer)) buttons.add(button(ctx, "like", exhibit.likes.contains(viewer) ? "panel.unlike" : "panel.like", Material.EMERALD, exhibit.map, false, false, false));
                    if (plugin.galleryPrints()) buttons.add(button(ctx, "print-all", "panel.print-all", Material.PAPER, exhibit.map, false, true, false));
                    if (exhibit.photographer.equals(viewer) || p.hasPermission("vystormsnapshot.gallery.moderate")) buttons.add(button(ctx, "unpublish", "panel.unpublish", Material.BARRIER, exhibit.map, true, true, false));
                    rows.add(photo(ctx, photo, buttons, ctx.text("gallery-likes", "likes", exhibit.likes.size())));
                }
                footer.add(button(ctx, "sort", s.top ? "gallery-sort-new" : "gallery-sort-top", Material.CLOCK, "", false, false, false));
            }
            case "contest" -> {
                icon = Material.GOLD_INGOT;
                title = ctx.text("contest.title", "theme", contest.theme(), "time", SnapshotPlugin.duration(Math.max(0, (contest.ends() - System.currentTimeMillis() + 59999) / 60000)));
                for (var entry : contest.ranking()) {
                    var photo = album.entry(entry.map); if (photo == null) continue;
                    var buttons = entry.photographer.equals(viewer) ? List.<Button>of() : List.of(button(ctx, "vote", entry.votes.contains(viewer) ? "panel.unvote" : "panel.vote", Material.EMERALD, entry.map, false, false, false));
                    rows.add(photo(ctx, photo, buttons, ctx.text("contest.entry-by", "player", entry.name, "votes", entry.votes.size())));
                }
                footer.add(button(ctx, "enter-picker", "contest.enter-button", Material.WRITABLE_BOOK, "", false, false, true));
            }
            case "contest-picker" -> {
                for (var exhibit : gallery.order(false, viewer)) {
                    var photo = album.entry(exhibit.map); if (photo != null) rows.add(photo(ctx, photo,
                            List.of(button(ctx, "enter", "contest.enter-button", Material.WRITABLE_BOOK, exhibit.map, false, true, false)), ""));
                }
                footer.add(button(ctx, "back-contest", "contest.back", Material.ARROW, "", false, false, true));
            }
            case "collections" -> {
                for (var c : collections.data.visible(viewer)) rows.add(new Row(c.id().toString(), c.title(),
                        ctx.text("collections.summary", "name", c.name(), "count", c.photos().size()), Material.BOOK,
                        List.of(new Stat(ctx.text("panel.photos"), Integer.toString(c.photos().size()))),
                        List.of(button(ctx, "open-collection", "panel.open", Material.BOOK, c.id(), false, false, true))));
                footer.add(button(ctx, "create", "collections.create", Material.WRITABLE_BOOK, "", false, false, true));
            }
            case "collection", "sharing" -> {
                var c = collections.data.get(s.target);
                if (c == null || !c.visible(viewer)) break;
                title = c.title();
                if (name.equals("sharing")) {
                    if (!c.owner().equals(viewer)) break;
                    for (UUID reader : c.readers().stream().sorted().toList()) {
                        String readerName = Optional.ofNullable(Bukkit.getOfflinePlayer(reader).getName()).orElse(reader.toString());
                        rows.add(new Row(reader.toString(), readerName, "", Material.PLAYER_HEAD, List.of(),
                                List.of(button(ctx, "revoke", "panel.revoke", Material.BARRIER, reader, true, true, false))));
                    }
                    footer.add(button(ctx, "share", "collections.share", Material.EMERALD, c.id(), false, false, true));
                    footer.add(button(ctx, "open-collection", "contest.back", Material.ARROW, c.id(), false, false, true));
                } else {
                    for (int map : c.photos()) {
                        var photo = album.entry(map); if (photo == null || !photo.photographer().equals(c.owner())) continue;
                        List<Button> buttons = new ArrayList<>();
                        buttons.add(button(ctx, "print-all", "panel.print-all", Material.PAPER, map, false, true, false));
                        if (c.owner().equals(viewer)) buttons.add(button(ctx, "remove-photo", "panel.remove-photo", Material.BARRIER, map, true, true, false));
                        rows.add(photo(ctx, photo, buttons, ""));
                    }
                    if (c.owner().equals(viewer)) {
                        footer.add(button(ctx, "add-held", "collections.add-held", Material.PAPER, c.id(), false, false, false));
                        footer.add(button(ctx, "rename", "collections.rename", Material.NAME_TAG, c.id(), false, false, true));
                        footer.add(button(ctx, "sharing", "collections.sharing", Material.PLAYER_HEAD, c.id(), false, false, true));
                        footer.add(button(ctx, "delete", "collections.delete", Material.BARRIER, c.id(), true, true, true));
                    }
                    footer.add(button(ctx, "collections", "collections.back", Material.ARROW, "", false, false, true));
                }
            }
            default -> throw new IllegalArgumentException(name);
        }
        if (name.equals("camera") || name.equals("album")) {
            if (!name.equals("album")) footer.add(button(ctx, "album", "menu-album", Material.BOOK, "", false, false, true));
            footer.add(button(ctx, "collections", "collections.title", Material.WRITABLE_BOOK, "", false, false, true));
            if (plugin.galleryOn(p)) footer.add(button(ctx, "gallery", "menu-gallery", Material.PAINTING, "", false, false, true));
        }
        int pages = Math.max(1, (rows.size() + 7) / 8); s.page = Math.clamp(s.page, 0, pages - 1);
        List<Row> slice = rows.subList(s.page * 8, Math.min(rows.size(), (s.page + 1) * 8));
        return new View(title, subtitle, icon, List.copyOf(slice), s.selected, s.page, pages,
                name.equals("album") ? Objects.requireNonNullElse(s.query, "") : null, choices, footer);
    }

    private Row photo(PanelContext ctx, Album.Entry photo, List<Button> buttons, String extra) {
        String title = photo.caption().isEmpty() ? ctx.text("photo-name") + " #" + photo.map() : photo.caption();
        return new Row(Integer.toString(photo.map()), title, extra.isEmpty() ? photo.name() : extra, Material.FILLED_MAP,
                List.of(new Stat(ctx.text("panel.photographer"), photo.name()), new Stat(ctx.text("panel.world"), photo.world()),
                        new Stat(ctx.text("panel.date"), photo.date()), new Stat(ctx.text("panel.location"), photo.where()),
                        new Stat(ctx.text("panel.maps"), Integer.toString(album.set(photo.map()).size()))), buttons);
    }

    private static Row withFields(Row row, List<Field> fields) {
        return new Row(row.id(), row.title(), row.subtitle(), row.icon(), row.stats(), row.buttons(), List.copyOf(fields));
    }

    private static Button button(PanelContext ctx, String id, String key, Material icon, Object value, boolean danger, boolean confirm, boolean leave) {
        return new Button(id, ctx.text(key), icon, value.toString(), danger, confirm, leave);
    }

    Result run(Player p, String name, String action, String value) {
        if (!allowed(p, name)) return Result.error(lang.plain(p, "denied"));
        State s = state(p, name);
        switch (action) {
            case "select" -> { s.selected = value; return Result.ok(); }
            case "tab" -> { s.selected = value; return Result.ok(); }
            case "page" -> { s.page = Math.max(0, Integer.parseInt(value)); s.selected = null; return Result.ok(); }
            case "search" -> { s.query = value; s.page = 0; s.selected = null; return Result.ok(); }
            case "sort" -> { s.top = !s.top; s.page = 0; s.selected = null; return Result.ok(); }
            case "style", "format", "frame", "timer" -> {
                boolean valid = switch (action) {
                    case "style" -> plugin.styles().stream().anyMatch(v -> v.id.equals(value));
                    case "frame" -> plugin.frames().stream().anyMatch(v -> v.id.equals(value));
                    case "format" -> plugin.formats().contains(value);
                    default -> plugin.timers().stream().anyMatch(v -> v.toString().equals(value));
                };
                ItemStack camera = held(p, s);
                if (!valid || !plugin.isCamera(camera)) return Result.error(lang.plain(p, "hold-camera"));
                plugin.cameraSetting(p, camera, action + ":" + value, false);
                if (s.slot >= 0 && s.slot < 9) p.getInventory().setItem(s.slot, camera);
                s.selected = "tab-" + action; return Result.ok();
            }
            case "album" -> { plugin.openAlbum(p, 0); return Result.ok(); }
            case "gallery" -> { plugin.openGallery(p, 0, false, null, null); return Result.ok(); }
            case "collections" -> { collections.list(p, 0); return Result.ok(); }
            case "open-collection" -> { collections.open(p, UUID.fromString(value), 0); return Result.ok(); }
            case "enter-picker" -> { plugin.openContestPicker(p); return Result.ok(); }
            case "back-contest" -> { plugin.openContest(p); return Result.ok(); }
            case "create" -> { collections.prompt(p, "name-prompt", "", text -> { send(p, collections.data.create(p.getUniqueId(), text)); collections.list(p, 0); }); return Result.ok(); }
            case "greeting" -> {
                if (!name.equals("album") || !plugin.postcardsOn(p)) return Result.error(lang.plain(p, "denied"));
                String text = Postcards.clean(value);
                if (text == null) return Result.error(lang.plain(p, "postcard.too-long", "max", Postcards.MAX_TEXT));
                s.greeting = text; return Result.ok();
            }
        }
        if (action.startsWith("caption:") && name.equals("album")) {
            int map;
            try { map = Integer.parseInt(action.substring("caption:".length())); } catch (NumberFormatException invalid) { return Result.error(lang.plain(p, "denied")); }
            SnapshotPlugin.Msg msg = plugin.caption(p, map, value);
            String text = lang.plain(p, msg.key(), msg.args());
            return msg.ok() ? Result.ok(text) : Result.error(text);
        }
        if (name.equals("collection") || name.equals("sharing")) return collectionAction(p, s, action, value);
        int map;
        try { map = Integer.parseInt(value); } catch (NumberFormatException invalid) { return Result.error(lang.plain(p, "denied")); }
        var photo = album.entry(map); var exhibit = gallery.exhibit(map);
        boolean owner = photo != null && photo.photographer().equals(p.getUniqueId());
        if (name.equals("album") && !owner) return Result.error(lang.plain(p, "denied"));
        if ((name.equals("gallery") || name.equals("contest-picker")) && exhibit == null) return Result.error(lang.plain(p, "collections.result-missing"));
        switch (action) {
            case "print", "print-all" -> {
                if (name.equals("gallery")) plugin.galleryPrint(p, map);
                else if (name.equals("album") && owner) plugin.reprint(p, map, action.equals("print-all"));
                else return Result.error(lang.plain(p, "denied"));
            }
            case "publish" -> { if (!plugin.galleryOn(p) || !owner) return Result.error(lang.plain(p, "denied")); plugin.togglePublish(p, map); }
            case "unpublish" -> { if (exhibit == null || (!exhibit.photographer.equals(p.getUniqueId()) && !p.hasPermission("vystormsnapshot.gallery.moderate"))) return Result.error(lang.plain(p, "denied")); plugin.unpublish(p, map); }
            case "like" -> plugin.like(p, map);
            case "vote" -> {
                String result = plugin.contestVote(p, map, false);
                return "voted".equals(result) || "unvoted".equals(result) ? Result.ok() : Result.error(lang.plain(p, "contest.vote-" + result));
            }
            case "enter" -> {
                if (exhibit == null || !exhibit.photographer.equals(p.getUniqueId())) return Result.error(lang.plain(p, "denied"));
                String result = plugin.contestEnter(p, map, false);
                return "ok".equals(result) ? Result.ok(lang.plain(p, "contest.enter-ok")) : Result.error(lang.plain(p, "contest.enter-" + result));
            }
            default -> { return Result.error(lang.plain(p, "denied")); }
        }
        return Result.ok();
    }

    private Result collectionAction(Player p, State s, String action, String value) {
        var c = collections.data.get(s.target);
        if (c == null || !c.visible(p.getUniqueId())) return Result.error(lang.plain(p, "collections.result-denied"));
        if (action.equals("print-all") || action.equals("remove-photo")) {
            int map = Integer.parseInt(value); var photo = album.entry(map);
            if (!c.photos().contains(map) || photo == null || !photo.photographer().equals(c.owner())) return Result.error(lang.plain(p, "collections.result-denied"));
            if (action.equals("print-all")) { plugin.reprint(p, map, true); return Result.ok(); }
            return result(p, collections.data.photo(p.getUniqueId(), c.id(), map, photo.photographer(), false));
        }
        if (!c.owner().equals(p.getUniqueId())) return Result.error(lang.plain(p, "collections.result-denied"));
        switch (action) {
            case "delete" -> {
                PhotoCollections.Result result = collections.data.delete(p.getUniqueId(), c.id());
                send(p, result);
                if (result == PhotoCollections.Result.SAVED) collections.list(p, 0);
                return result(p, result);
            }
            case "revoke" -> { return result(p, collections.data.share(p.getUniqueId(), c.id(), UUID.fromString(value), false)); }
            case "add-held" -> collections.addHeld(p, c.id(), held(p, s));
            case "sharing" -> collections.sharing(p, c.id());
            case "rename" -> collections.prompt(p, "title-prompt", c.title(), text -> { send(p, collections.data.title(p.getUniqueId(), c.id(), text)); collections.open(p, c.id(), 0); });
            case "share" -> collections.prompt(p, "player-prompt", "", text -> {
                var target = text == null ? null : core.compat().platform().getOfflinePlayerIfCached(text.strip());
                if (target == null) lang.send(p, "unknown-player");
                else send(p, collections.data.share(p.getUniqueId(), c.id(), target.getUniqueId(), true));
                collections.sharing(p, c.id());
            });
            default -> { return Result.error(lang.plain(p, "denied")); }
        }
        return Result.ok();
    }

    private Result result(Player p, PhotoCollections.Result result) {
        String message = lang.plain(p, "collections.result-" + result.name().toLowerCase(Locale.ROOT));
        return result == PhotoCollections.Result.SAVED ? Result.ok(message) : Result.error(message);
    }
    private void send(Player p, PhotoCollections.Result result) { lang.send(p, "collections.result-" + result.name().toLowerCase(Locale.ROOT)); }
}
