package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.compat.InventoryCompatInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
import org.bukkit.map.MapView;

/**
 * The public photo gallery (photos/gallery.yml): players put their best photos on show from the album, everyone can
 * browse them newest or most liked first, like them and take a print. A big photo is one exhibit (its first map).
 */
final class Gallery {
    /** One photo on show; {@code likes} are the players who like it. */
    static final class Exhibit {
        final int map;
        final UUID photographer;
        final String name, date;
        final long published;
        final Set<UUID> likes = new LinkedHashSet<>();

        Exhibit(int map, UUID photographer, String name, String date, long published) {
            this.map = map;
            this.photographer = photographer;
            this.name = name;
            this.date = date;
            this.published = published;
        }
    }

    enum Like { MISSING, OWN, LIKED, UNLIKED }

    private final Path file;
    private final Logger logger;
    private final AtomicFilesInterface files;
    private final PlatformCompatInterface platform;
    private final Map<Integer, Exhibit> exhibits = new LinkedHashMap<>();
    /** Name the unreadable gallery.yml was moved to; while set the gallery is read-only and never saved. */
    private String broken;
    /** Changes not yet written; {@link #flush()} writes them (plugin timer and disable). */
    private boolean dirty;

    /** @param files {@code api.data().files()}; @param platform {@code api.compat().platform()} */
    Gallery(Path file, Logger logger, AtomicFilesInterface files, PlatformCompatInterface platform) {
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
            // Never start empty and overwrite the photos on the next save: move the file aside and stay read-only.
            String aside = file.getFileName() + ".broken-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            try { Files.move(file, file.resolveSibling(aside)); broken = aside; }
            catch (IOException moveFailed) { broken = file.getFileName().toString(); }
            logger.severe("gallery.yml could not be read (" + failure.getMessage() + "). It was kept as photos/" + broken
                    + " and the gallery is switched off until the next restart. Fix that file, rename it back to gallery.yml and restart;"
                    + " otherwise the gallery starts empty next time.");
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("exhibits");
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            ConfigurationSection c = section.getConfigurationSection(key);
            try {
                Exhibit exhibit = new Exhibit(Integer.parseInt(key), UUID.fromString(c.getString("by")), c.getString("name", "?"),
                        c.getString("date", ""), c.getLong("published"));
                for (String like : c.getStringList("likes")) {
                    try { exhibit.likes.add(UUID.fromString(like)); } catch (IllegalArgumentException broken) { }
                }
                exhibits.put(exhibit.map, exhibit);
            } catch (RuntimeException broken) { }
        }
    }

    /** Marks the gallery as changed; the plugin writes it at most every few seconds and on disable (likes come fast). */
    private void save() { dirty = true; }

    /** The file name the unreadable gallery.yml was moved to, or null when the gallery loaded fine. */
    String broken() { return broken; }

    /** Writes pending changes atomically; nothing happens when nothing changed or the gallery is read-only. */
    void flush() {
        if (!dirty || broken != null) return;
        dirty = false;
        YamlConfiguration yaml = new YamlConfiguration();
        for (Exhibit e : exhibits.values()) {
            String base = "exhibits." + e.map;
            yaml.set(base + ".by", e.photographer.toString());
            yaml.set(base + ".name", e.name);
            yaml.set(base + ".date", e.date);
            yaml.set(base + ".published", e.published);
            if (!e.likes.isEmpty()) yaml.set(base + ".likes", e.likes.stream().map(UUID::toString).toList());
        }
        try { files.write(file, yaml); }
        catch (IOException failure) { dirty = true; logger.warning("Could not save gallery.yml: " + failure.getMessage()); }
    }

    Exhibit exhibit(int map) { return exhibits.get(map); }

    /** Every photo on show as a read-only view (server thread; the rankings count it). */
    Collection<Exhibit> all() { return java.util.Collections.unmodifiableCollection(exhibits.values()); }

    boolean shown(int map) { return exhibits.containsKey(map); }

    int count(UUID photographer) {
        int count = 0;
        for (Exhibit exhibit : exhibits.values()) if (exhibit.photographer.equals(photographer)) count++;
        return count;
    }

    void publish(int map, UUID photographer, String name, String date, long now) {
        if (broken != null) return;
        exhibits.put(map, new Exhibit(map, photographer, name, date, now));
        save();
    }

    boolean remove(int map) {
        if (broken != null) return false;
        if (exhibits.remove(map) == null) return false;
        save();
        return true;
    }

    /** Toggles the like of {@code player}; photographers cannot like their own photos. */
    Like like(int map, UUID player) {
        Exhibit exhibit = exhibits.get(map);
        if (exhibit == null || broken != null) return Like.MISSING;
        if (exhibit.photographer.equals(player)) return Like.OWN;
        Like result = exhibit.likes.remove(player) ? Like.UNLIKED : Like.LIKED;
        if (result == Like.LIKED) exhibit.likes.add(player);
        save();
        return result;
    }

    /** Exhibits newest first, or most liked first (newest wins a tie); {@code onlyBy} limits them to one photographer. */
    static List<Exhibit> order(Collection<Exhibit> all, boolean top, UUID onlyBy) {
        List<Exhibit> out = new ArrayList<>();
        for (Exhibit exhibit : all) if (onlyBy == null || exhibit.photographer.equals(onlyBy)) out.add(exhibit);
        Comparator<Exhibit> newest = Comparator.comparingLong((Exhibit e) -> e.published).reversed().thenComparing(e -> -e.map);
        out.sort(top ? Comparator.comparingInt((Exhibit e) -> e.likes.size()).reversed().thenComparing(newest) : newest);
        return out;
    }

    List<Exhibit> order(boolean top, UUID onlyBy) { return order(exhibits.values(), top, onlyBy); }

    static final int PER_PAGE = 45;

    static int pages(int count) { return Math.max(1, (count + PER_PAGE - 1) / PER_PAGE); }

    /** The gallery menu; {@code onlyBy} shows the photos of one player (from the Core player menu). */
    Inventory menu(SnapshotPlugin plugin, Lang lang, Player viewer, int page, boolean top, UUID onlyBy, String onlyName) {
        Holder holder = new Holder();
        holder.top = top;
        holder.onlyBy = onlyBy;
        holder.onlyName = onlyName;
        List<Exhibit> shown = order(top, onlyBy);
        int pages = pages(shown.size());
        holder.page = Math.clamp(page, 0, pages - 1);
        String count = Integer.toString(shown.size());
        Component title = onlyBy != null
                ? lang.component(viewer, "gallery-title-player", "player", onlyName, "count", count, "page", Integer.toString(holder.page + 1), "pages", Integer.toString(pages))
                : lang.component(viewer, "gallery-title", "count", count, "page", Integer.toString(holder.page + 1), "pages", Integer.toString(pages));
        Inventory inventory = platform.createInventory(holder, 54, title);
        holder.inventory = inventory;
        boolean moderator = viewer.hasPermission("vystormsnapshot.gallery.moderate");
        for (Exhibit exhibit : shown.subList(holder.page * PER_PAGE, Math.min(shown.size(), (holder.page + 1) * PER_PAGE))) {
            MapView view = Bukkit.getMap(exhibit.map);
            if (view == null) continue;
            ItemStack icon = Album.icon(platform, view, plugin.albumEntry(exhibit.map), lang, viewer);
            int maps = plugin.photoSet(exhibit.map).size();
            boolean own = exhibit.photographer.equals(viewer.getUniqueId());
            platform.editMeta(icon, meta -> {
                List<Component> lore = platform.lore(meta) == null ? new ArrayList<>() : new ArrayList<>(platform.lore(meta));
                lore.add(lang.component(viewer, exhibit.likes.contains(viewer.getUniqueId()) ? "gallery-likes-yours" : "gallery-likes",
                        "likes", Integer.toString(exhibit.likes.size())));
                if (!own) lore.add(lang.component(viewer, exhibit.likes.contains(viewer.getUniqueId()) ? "gallery-unlike" : "gallery-like"));
                if (plugin.galleryPrints()) lore.add(lang.component(viewer, "gallery-print", "maps", Integer.toString(maps)));
                if (own || moderator) lore.add(lang.component(viewer, "gallery-remove"));
                platform.lore(meta, lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
            });
            inventory.setItem(holder.maps.size(), icon);
            holder.maps.add(exhibit.map);
        }
        if (holder.page > 0) inventory.setItem(45, Album.arrow(platform, lang.component(viewer, "album-previous", "page", Integer.toString(holder.page))));
        ItemStack sort = new ItemStack(top ? Material.GOLD_INGOT : Material.CLOCK);
        platform.editMeta(sort, meta -> {
            platform.displayName(meta, lang.component(viewer, top ? "gallery-sort-top" : "gallery-sort-new").decoration(TextDecoration.ITALIC, false));
            platform.lore(meta, List.of(lang.component(viewer, "gallery-sort-hint").decoration(TextDecoration.ITALIC, false)));
        });
        inventory.setItem(49, sort);
        if (holder.page < pages - 1) inventory.setItem(53, Album.arrow(platform, lang.component(viewer, "album-next", "page", Integer.toString(holder.page + 2))));
        return inventory;
    }

    private static final class Holder implements InventoryHolder {
        private final List<Integer> maps = new ArrayList<>();
        private Inventory inventory;
        private int page;
        private boolean top;
        private UUID onlyBy;
        private String onlyName;

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
            int page = holder.page;
            if (slot >= 0 && slot < holder.maps.size()) {
                int map = holder.maps.get(slot);
                if (event.isShiftClick() && event.isRightClick()) plugin.unpublish(player, map);
                else if (event.isShiftClick()) { plugin.galleryPrint(player, map); return; }
                else plugin.like(player, map);
            } else if (slot == 45 && event.getCurrentItem() != null) page--;
            else if (slot == 53 && event.getCurrentItem() != null) page++;
            else if (slot == 49) { holder.top = !holder.top; page = 0; }
            else return;
            plugin.openGallery(player, page, holder.top, holder.onlyBy, holder.onlyName);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (inventories.top(event.getView()).getHolder() instanceof Holder) event.setCancelled(true);
        }
    }
}
