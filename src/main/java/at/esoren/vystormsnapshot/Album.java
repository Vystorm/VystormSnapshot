package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.compat.InventoryCompatInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;

/** Who took which photo (photos/album.yml) and the album menu to look at and reprint your own photos. */
final class Album {
    record Entry(int map, UUID photographer, String name, String world, String where, String date, String part, String caption) {
        Entry(int map, UUID photographer, String name, String world, String where, String date, String part) {
            this(map, photographer, name, world, where, date, part, "");
        }
        Entry caption(String text) { return new Entry(map, photographer, name, world, where, date, part, text); }
    }

    enum Caption { SAVED, MISSING, NOT_OWNER, INVALID, UNAVAILABLE }

    private final Path file;
    private final Logger logger;
    private final AtomicFilesInterface files;
    private final PlatformCompatInterface platform;
    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Object> unreadable = new LinkedHashMap<>();
    /** Name the unreadable album.yml was moved to; while set the album is never saved. */
    private String broken;

    /** @param files {@code api.data().files()}; @param platform {@code api.compat().platform()} */
    Album(Path file, Logger logger, AtomicFilesInterface files, PlatformCompatInterface platform) {
        this.file = file;
        this.logger = logger;
        this.files = files;
        this.platform = platform;
        load();
    }

    private void load() {
        if (!Files.exists(file)) return;
        YamlConfiguration yaml = new YamlConfiguration();
        try { yaml.load(file.toFile()); }
        catch (IOException | org.bukkit.configuration.InvalidConfigurationException failure) {
            // Never start empty and overwrite the album with the next photo: move the file aside and stop saving.
            String aside = file.getFileName() + ".broken-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            try { Files.move(file, file.resolveSibling(aside)); broken = aside; }
            catch (IOException moveFailed) { broken = file.getFileName().toString(); }
            logger.severe("album.yml could not be read (" + failure.getMessage() + "). It was kept as photos/" + broken
                    + "; new photos are not added to the album until the next restart. Fix that file, rename it back to album.yml and restart.");
            return;
        }
        ConfigurationSection photos = yaml.getConfigurationSection("photos");
        if (photos == null && yaml.contains("photos")) {
            broken = file.getFileName().toString();
            logger.severe("album.yml: photos must be a section; the file is unchanged and saving is disabled until restart.");
            return;
        }
        if (photos == null) return;
        for (String key : photos.getKeys(false)) {
            ConfigurationSection c = photos.getConfigurationSection(key);
            try {
                int map = Integer.parseInt(key);
                entries.put(map, new Entry(map, UUID.fromString(c.getString("by")), c.getString("name", "?"), c.getString("world", ""),
                        c.getString("where", ""), c.getString("date", ""), c.getString("part", ""), c.getString("caption", "")));
            } catch (RuntimeException invalid) {
                Object raw = photos.get(key);
                unreadable.put(key, raw instanceof ConfigurationSection section ? snapshot(section) : raw);
                logger.warning("album.yml: invalid photo '" + key + "' is unavailable; its original data is preserved.");
            }
        }
    }

    private static Map<String, Object> snapshot(ConfigurationSection section) {
        Map<String, Object> values = new LinkedHashMap<>();
        section.getValues(false).forEach((key, value) -> values.put(key,
                value instanceof ConfigurationSection nested ? snapshot(nested) : value));
        return values;
    }

    void add(Entry entry) {
        if (unreadable.containsKey(Integer.toString(entry.map()))) {
            logger.warning("album.yml: refusing to replace invalid photo " + entry.map() + "; repair its data first.");
            return;
        }
        entries.put(entry.map(), entry);
        save();
    }

    private boolean save() {
        if (broken != null) return false;
        YamlConfiguration yaml = new YamlConfiguration();
        unreadable.forEach((key, value) -> yaml.set("photos." + key, value));
        for (Entry e : entries.values()) {
            String base = "photos." + e.map();
            yaml.set(base + ".by", e.photographer().toString());
            yaml.set(base + ".name", e.name());
            yaml.set(base + ".world", e.world());
            yaml.set(base + ".where", e.where());
            yaml.set(base + ".date", e.date());
            if (!e.part().isEmpty()) yaml.set(base + ".part", e.part());
            if (!e.caption().isEmpty()) yaml.set(base + ".caption", e.caption());
        }
        try { files.write(file, yaml); }
        catch (IOException failure) { logger.warning("Could not save album.yml: " + failure.getMessage()); return false; }
        return true;
    }

    /** Update the whole picture in one atomic write; a failed save leaves the old captions intact. */
    Caption caption(int map, UUID player, String text, int maxLength) {
        if (broken != null) return Caption.UNAVAILABLE;
        Entry entry = entries.get(map);
        if (entry == null) return Caption.MISSING;
        if (!entry.photographer().equals(player)) return Caption.NOT_OWNER;
        String value = text == null ? "" : text.strip();
        if (value.codePointCount(0, value.length()) > maxLength
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0x00a7
                        || c == 0x2028 || c == 0x2029)) return Caption.INVALID;
        Map<Integer, Entry> before = new LinkedHashMap<>();
        for (int id : set(map)) {
            Entry old = entries.get(id);
            before.put(id, old);
            entries.put(id, old.caption(value));
        }
        if (save()) return Caption.SAVED;
        entries.putAll(before);
        return Caption.UNAVAILABLE;
    }

    Entry entry(int map) { return entries.get(map); }

    /** Every photo, oldest first, as a read-only view (server thread; the rankings count it). */
    java.util.Collection<Entry> all() { return java.util.Collections.unmodifiableCollection(entries.values()); }

    /** The file name the unreadable album.yml was moved to, or null when the album loaded fine. */
    String broken() { return broken; }

    /** All maps of the big photo this map belongs to, see {@link #set(Entry, java.util.function.IntFunction)}. */
    List<Integer> set(int map) {
        Entry entry = entries.get(map);
        return entry == null ? List.of(map) : set(entry, entries::get);
    }

    /** The newest photos of a player first. */
    List<Entry> of(UUID player) { return of(player, null); }

    List<Entry> of(UUID player, String world) {
        return of(player, world, null);
    }

    List<Entry> of(UUID player, String world, String query) {
        String needle = query == null ? "" : query.strip().toLowerCase(java.util.Locale.ROOT);
        List<Entry> out = new ArrayList<>();
        for (Entry entry : entries.values()) if (entry.photographer().equals(player)
                && (world == null || entry.world().equalsIgnoreCase(world))
                && (needle.isEmpty() || entry.caption().toLowerCase(java.util.Locale.ROOT).contains(needle))) out.add(entry);
        java.util.Collections.reverse(out);
        return out;
    }

    /** Stored worlds, including unloaded ones; only suggest the player's own photo locations. */
    List<String> worlds(UUID player) {
        return of(player).stream().map(Entry::world).filter(world -> !world.isBlank()).distinct().sorted().toList();
    }

    /** A map item showing the photo with name and lore. */
    static ItemStack icon(PlatformCompatInterface platform, MapView view, Entry entry, Lang lang, Player viewer) {
        ItemStack photo = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) photo.getItemMeta();
        meta.setMapView(view);
        platform.displayName(meta, lang.component(viewer, entry == null || entry.part().isEmpty() ? "photo-name" : "photo-name-part", "part", entry == null ? "" : entry.part())
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        if (entry != null) {
            if (!entry.caption().isEmpty()) lore.add(lang.component(viewer, "photo-caption")
                    .append(Component.text(entry.caption())).decoration(TextDecoration.ITALIC, false));
            lore.add(lang.component(viewer, "photo-lore", "player", entry.name(), "world", entry.world(), "date", entry.date()).decoration(TextDecoration.ITALIC, false));
            if (!entry.where().isEmpty()) lore.add(lang.component(viewer, "photo-where", "where", entry.where()).decoration(TextDecoration.ITALIC, false));
        }
        platform.lore(meta, lore);
        photo.setItemMeta(meta);
        return photo;
    }

    /** Up to 54 photos fit on one page; more get 45 per page and a row with page arrows. */
    static int perPage(int count) { return count <= 54 ? 54 : 45; }

    static int pages(int count) { return Math.max(1, (count + perPage(count) - 1) / perPage(count)); }

    /**
     * All maps of the big photo {@code clicked} belongs to ("2/6" → the six maps in order), or just {@code clicked}
     * when it is a single photo or a part of the set is missing.
     */
    static List<Integer> set(Entry clicked, java.util.function.IntFunction<Entry> lookup) {
        String[] part = clicked.part().split("/");
        if (part.length != 2) return List.of(clicked.map());
        try {
            int index = Integer.parseInt(part[0]), total = Integer.parseInt(part[1]);
            if (index < 1 || index > total || total > 16) return List.of(clicked.map());
            List<Integer> maps = new ArrayList<>();
            for (int i = 1; i <= total; i++) {
                Entry other = lookup.apply(clicked.map() - index + i);
                if (other == null || !other.photographer().equals(clicked.photographer()) || !other.part().equals(i + "/" + total))
                    return List.of(clicked.map());
                maps.add(other.map());
            }
            return maps;
        } catch (NumberFormatException wrong) {
            return List.of(clicked.map());
        }
    }

    /** {@code gallery}: null while the gallery is switched off, otherwise each photo shows whether it is on show. */
    Inventory menu(Lang lang, Player player, int page, Gallery gallery, String world) {
        return menu(lang, player, page, gallery, world, null);
    }

    Inventory menu(Lang lang, Player player, int page, Gallery gallery, String world, String query) {
        return menu(lang, player, page, gallery, world, query, false, false);
    }

    /**
     * {@code postcards}: the player may send postcards (Q on a photo); {@code captions}: captions are on (Ctrl+Q edits
     * the caption). Both 1.22.0; off, the photo lore is as before.
     */
    Inventory menu(Lang lang, Player player, int page, Gallery gallery, String world, String query, boolean postcards, boolean captions) {
        Holder holder = new Holder();
        holder.world = world;
        holder.query = query;
        List<Entry> mine = of(player.getUniqueId(), world, query);
        int pages = pages(mine.size()), perPage = perPage(mine.size());
        holder.page = Math.clamp(page, 0, pages - 1);
        int size = pages > 1 ? 54 : Math.clamp((mine.size() + 8) / 9 * 9, 9, 54);
        Component title = query != null
                ? lang.component(player, "album-title-search", "count", Integer.toString(mine.size()),
                        "page", Integer.toString(holder.page + 1), "pages", Integer.toString(pages))
                : world != null
                ? lang.component(player, "album-title-world", "world", world, "count", Integer.toString(mine.size()),
                        "page", Integer.toString(holder.page + 1), "pages", Integer.toString(pages))
                : pages > 1
                ? lang.component(player, "album-title-page", "count", Integer.toString(mine.size()), "page", Integer.toString(holder.page + 1), "pages", Integer.toString(pages))
                : lang.component(player, "album-title", "count", Integer.toString(mine.size()));
        Inventory inventory = platform.createInventory(holder, size, title);
        holder.inventory = inventory;
        for (Entry entry : mine.subList(holder.page * perPage, Math.min(mine.size(), (holder.page + 1) * perPage))) {
            MapView view = Bukkit.getMap(entry.map());
            if (view == null) continue;
            ItemStack icon = icon(platform, view, entry, lang, player);
            int maps = set(entry, entries::get).size();
            platform.editMeta(icon, meta -> {
                List<Component> lore = platform.lore(meta) == null ? new ArrayList<>() : new ArrayList<>(platform.lore(meta));
                lore.add(lang.component(player, captions ? "album-caption-key" : "album-caption-hint").decoration(TextDecoration.ITALIC, false));
                lore.add(lang.component(player, "album-reprint").decoration(TextDecoration.ITALIC, false));
                if (postcards) lore.add(lang.component(player, "album-postcard-key").decoration(TextDecoration.ITALIC, false));
                if (maps > 1) lore.add(lang.component(player, "album-reprint-all", "maps", Integer.toString(maps)).decoration(TextDecoration.ITALIC, false));
                if (gallery != null) {
                    Gallery.Exhibit exhibit = gallery.exhibit(set(entry.map()).getFirst());
                    lore.add((exhibit == null ? lang.component(player, "album-publish")
                            : lang.component(player, "album-published", "likes", Integer.toString(exhibit.likes.size()))).decoration(TextDecoration.ITALIC, false));
                }
                platform.lore(meta, lore);
            });
            inventory.setItem(holder.maps.size(), icon);
            holder.maps.add(entry.map());
        }
        if (pages > 1) {
            if (holder.page > 0) inventory.setItem(45, arrow(platform, lang.component(player, "album-previous", "page", Integer.toString(holder.page))));
            if (holder.page < pages - 1) inventory.setItem(53, arrow(platform, lang.component(player, "album-next", "page", Integer.toString(holder.page + 2))));
        }
        return inventory;
    }

    static ItemStack arrow(PlatformCompatInterface platform, Component name) {
        ItemStack arrow = new ItemStack(Material.ARROW);
        platform.editMeta(arrow, meta -> platform.displayName(meta, name.decoration(TextDecoration.ITALIC, false)));
        return arrow;
    }

    private static final class Holder implements InventoryHolder {
        private final List<Integer> maps = new ArrayList<>();
        private Inventory inventory;
        private int page;
        private String world;
        private String query;

        void reopen(SnapshotPlugin plugin, Player player, int page) {
            if (query == null) plugin.openAlbum(player, page, world);
            else plugin.openAlbum(player, page, world, query);
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    static final class Clicks implements Listener {
        private final SnapshotPlugin plugin;

        private final InventoryCompatInterface inventories;

        Clicks(SnapshotPlugin plugin, InventoryCompatInterface inventories) { this.plugin = plugin; this.inventories = inventories; }

        @EventHandler
        public void onClick(InventoryClickEvent event) {
            if (!(inventories.top(event.getView()).getHolder() instanceof Holder holder)) return;
            event.setCancelled(true);
            if (event.getClickedInventory() != inventories.top(event.getView()) || !(event.getWhoClicked() instanceof Player player)) return;
            int slot = event.getSlot();
            // 1.22.0: Q sends the photo as a postcard, Ctrl+Q edits its caption (each only while it is offered), one tick later
            if (slot >= 0 && slot < holder.maps.size() && event.getClick() == org.bukkit.event.inventory.ClickType.DROP && plugin.postcardsOn(player)) {
                int map = holder.maps.get(slot), page = holder.page;
                Bukkit.getScheduler().runTask(plugin, () -> plugin.postcardFromChest(player, map, () -> holder.reopen(plugin, player, page)));
                return;
            }
            if (slot >= 0 && slot < holder.maps.size() && event.getClick() == org.bukkit.event.inventory.ClickType.CONTROL_DROP && plugin.captionsOn()) {
                int map = holder.maps.get(slot), page = holder.page;
                Bukkit.getScheduler().runTask(plugin, () -> plugin.captionFromChest(player, map, () -> holder.reopen(plugin, player, page)));
                return;
            }
            if (slot >= 0 && slot < holder.maps.size() && event.isRightClick() && !event.isShiftClick() && plugin.galleryOn(player)) {
                plugin.togglePublish(player, holder.maps.get(slot));
                holder.reopen(plugin, player, holder.page);
            }
            else if (slot >= 0 && slot < holder.maps.size()) plugin.reprint(player, holder.maps.get(slot), event.isShiftClick());
            else if (slot == 45 && event.getCurrentItem() != null) holder.reopen(plugin, player, holder.page - 1);
            else if (slot == 53 && event.getCurrentItem() != null) holder.reopen(plugin, player, holder.page + 1);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (inventories.top(event.getView()).getHolder() instanceof Holder) event.setCancelled(true);
        }
    }
}
