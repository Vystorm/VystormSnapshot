package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.lang.Lang;
import java.util.*;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.MapMeta;

/** Owner-only editing and explicitly shared read access; every stale menu action rechecks the current state. */
final class CollectionsMenu implements Listener {
    private final SnapshotPlugin plugin;
    private final API core;
    final PhotoCollections data;
    private final Lang lang;
    CollectionsMenu(SnapshotPlugin plugin, API core, PhotoCollections data, Lang lang) { this.plugin = plugin; this.core = core; this.data = data; this.lang = lang; }
    private boolean allowed(Player p) { return p.isOnline() && p.hasPermission("vystormsnapshot.use"); }
    private void result(Player p, PhotoCollections.Result r) { lang.send(p, "collections.result-" + r.name().toLowerCase(Locale.ROOT)); }
    void prompt(Player p, String key, String initial, Consumer<String> response) {
        p.closeInventory();
        core.dialogs().prompt(plugin, p, lang.ref("collections.title"), lang.ref("collections." + key), initial,
                text -> { if (allowed(p)) response.accept(text); }, () -> {}, this::allowed);
    }
    /** The actions {@link #command} understands, in the order they are suggested. */
    static final List<String> ACTIONS = List.of("create", "open", "add", "title", "share", "unshare", "delete");
    /** Tab completion for the first word of {@code /snapshot albums}; later words are names or free text. */
    static List<String> suggestActions(String prefix) {
        String typed = prefix == null ? "" : prefix;
        if (!typed.isEmpty() && typed.stripTrailing().length() != typed.length()) return List.of();
        String lower = typed.toLowerCase(Locale.ROOT);
        return ACTIONS.stream().filter(a -> a.startsWith(lower)).toList();
    }
    void command(Player p, String input) {
        if (!allowed(p)) return;
        if (input == null || input.isBlank()) { list(p, 0); return; }
        String[] args = input.strip().split("\\s+", 3);
        if (args[0].equalsIgnoreCase("create") && args.length == 2) { result(p, data.create(p.getUniqueId(), args[1])); list(p, 0); return; }
        if (args.length < 2) { lang.send(p, "collections.usage"); return; }
        PhotoCollections.Collection c = data.own(p.getUniqueId(), args[1]);
        if (c == null) { result(p, PhotoCollections.Result.MISSING); return; }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "open" -> open(p, c.id(), 0);
            case "add" -> addHeld(p, c.id());
            case "title" -> { if (args.length == 3) result(p, data.title(p.getUniqueId(), c.id(), args[2])); else lang.send(p, "collections.usage"); }
            case "share", "unshare" -> {
                if (args.length != 3) { lang.send(p, "collections.usage"); return; }
                var target = core.compat().platform().getOfflinePlayerIfCached(args[2]);
                if (target == null) { lang.send(p, "unknown-player"); return; }
                result(p, data.share(p.getUniqueId(), c.id(), target.getUniqueId(), args[0].equalsIgnoreCase("share")));
            }
            case "delete" -> confirmDelete(p, c.id());
            default -> lang.send(p, "collections.usage");
        }
    }
    void list(Player p, int page) {
        if (plugin.openPanel(p, "collections", page, null, null, null, null, false)) return;
        listChest(p, page);
    }
    void listChest(Player p, int page) {
        if (!allowed(p)) return;
        List<PhotoCollections.Collection> visible = data.visible(p.getUniqueId());
        int at = Math.clamp(page, 0, Math.max(0, (visible.size() - 1) / 45));
        Holder h = holder(p, lang.component(p, "collections.title"));
        for (int i = at * 45; i < Math.min(visible.size(), (at + 1) * 45); i++) {
            var c = visible.get(i);
            ItemStack icon = item(Material.BOOK, Component.text(c.title()), lang.component(p, "collections.summary", "count", c.photos().size(), "name", c.name()));
            h.set(i % 45, icon, who -> open(who, c.id(), 0));
        }
        h.set(49, item(p, Material.WRITABLE_BOOK, "create"), who -> prompt(who, "name-prompt", "", text -> { result(who, data.create(who.getUniqueId(), text)); list(who, 0); }));
        if (at > 0) h.set(45, item(p, Material.ARROW, "previous"), who -> list(who, at - 1));
        if ((at + 1) * 45 < visible.size()) h.set(53, item(p, Material.ARROW, "next"), who -> list(who, at + 1));
        p.openInventory(h.inventory);
    }
    void open(Player p, UUID id, int page) {
        if (plugin.openPanel(p, "collection", page, null, null, id, null, false)) return;
        openChest(p, id, page);
    }
    void openChest(Player p, UUID id, int page) {
        if (!allowed(p)) return;
        var c = data.get(id);
        if (c == null || !c.visible(p.getUniqueId())) { result(p, PhotoCollections.Result.DENIED); p.closeInventory(); return; }
        int at = Math.clamp(page, 0, Math.max(0, (c.photos().size() - 1) / 45));
        Holder h = holder(p, Component.text(c.title())); h.collection = id; h.page = at;
        for (int i = at * 45; i < Math.min(c.photos().size(), (at + 1) * 45); i++) {
            int map = c.photos().get(i); var entry = plugin.albumEntry(map); var view = Bukkit.getMap(map);
            if (entry == null || !entry.photographer().equals(c.owner()) || view == null) continue;
            ItemStack icon = Album.icon(core.compat().platform(), view, entry, lang, p);
            core.compat().platform().editMeta(icon, meta -> {
                List<Component> lore = new ArrayList<>(Optional.ofNullable(core.compat().platform().lore(meta)).orElse(List.of()));
                lore.add(lang.component(p, c.owner().equals(p.getUniqueId()) ? "collections.photo-owner" : "collections.photo-reader"));
                core.compat().platform().lore(meta, lore);
            });
            int slot = i % 45; h.maps.put(slot, map); h.inventory.setItem(slot, icon);
        }
        h.set(48, item(p, Material.BOOK, "back"), who -> list(who, 0));
        if (c.owner().equals(p.getUniqueId())) {
            h.set(46, item(p, Material.PAPER, "add-held"), who -> { addHeld(who, id); open(who, id, at); });
            h.set(49, item(p, Material.NAME_TAG, "rename"), who -> prompt(who, "title-prompt", c.title(), text -> { result(who, data.title(who.getUniqueId(), id, text)); open(who, id, at); }));
            h.set(50, item(p, Material.PLAYER_HEAD, "sharing"), who -> sharing(who, id));
            h.set(51, item(p, Material.BARRIER, "delete"), who -> confirmDelete(who, id));
        }
        if (at > 0) h.set(45, item(p, Material.ARROW, "previous"), who -> open(who, id, at - 1));
        if ((at + 1) * 45 < c.photos().size()) h.set(53, item(p, Material.ARROW, "next"), who -> open(who, id, at + 1));
        p.openInventory(h.inventory);
    }
    void sharing(Player p, UUID id) {
        if (plugin.openPanel(p, "sharing", 0, null, null, id, null, false)) return;
        sharingChest(p, id);
    }
    void sharingChest(Player p, UUID id) {
        var c = data.get(id);
        if (!allowed(p) || c == null || !c.owner().equals(p.getUniqueId())) return;
        Holder h = holder(p, lang.component(p, "collections.sharing")); int slot = 0;
        for (UUID reader : c.readers().stream().sorted().toList()) {
            String name = Optional.ofNullable(Bukkit.getOfflinePlayer(reader).getName()).orElse(reader.toString());
            h.set(slot++, item(Material.PLAYER_HEAD, Component.text(name), lang.component(p, "collections.revoke")), who -> {
                result(who, data.share(who.getUniqueId(), id, reader, false)); sharing(who, id);
            });
        }
        h.set(49, item(p, Material.EMERALD, "share"), who -> prompt(who, "player-prompt", "", name -> {
            if (name == null) return;
            var target = core.compat().platform().getOfflinePlayerIfCached(name.strip());
            if (target == null) lang.send(who, "unknown-player");
            else result(who, data.share(who.getUniqueId(), id, target.getUniqueId(), true));
            sharing(who, id);
        }));
        h.set(45, item(p, Material.ARROW, "back"), who -> open(who, id, 0));
        p.openInventory(h.inventory);
    }
    private void confirmDelete(Player p, UUID id) {
        p.closeInventory();
        core.dialogs().confirm(plugin, p, lang.ref("collections.delete"), lang.ref("collections.delete-confirm"),
                () -> { if (allowed(p)) { result(p, data.delete(p.getUniqueId(), id)); list(p, 0); } }, () -> {}, this::allowed);
    }
    void addHeld(Player p, UUID id) {
        ItemStack held = p.getInventory().getItemInMainHand();
        if (!(held.getItemMeta() instanceof MapMeta map) || !map.hasMapView() || map.getMapView() == null) { result(p, PhotoCollections.Result.INVALID); return; }
        int first = plugin.photoSet(map.getMapView().getId()).getFirst(); var photo = plugin.albumEntry(first);
        if (photo == null) { result(p, PhotoCollections.Result.MISSING); return; }
        result(p, data.photo(p.getUniqueId(), id, first, photo.photographer(), true));
    }
    private ItemStack item(Player p, Material material, String key) { return item(material, lang.component(p, "collections." + key)); }
    private ItemStack item(Material material, Component title, Component... lore) {
        var platform = core.compat().platform();
        ItemStack item = new ItemStack(material);
        platform.editMeta(item, meta -> { platform.displayName(meta, title.decoration(TextDecoration.ITALIC, false)); platform.lore(meta, Arrays.asList(lore)); });
        return item;
    }
    private Holder holder(Player p, Component title) {
        Holder h = new Holder(p.getUniqueId()); h.inventory = core.compat().platform().createInventory(h, 54, title); return h;
    }
    @EventHandler public void click(InventoryClickEvent event) {
        if (!(core.compat().inventory().top(event.getView()).getHolder() instanceof Holder h)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player p) || !allowed(p) || !h.viewer.equals(p.getUniqueId())
                || event.getClickedInventory() != h.inventory) return;
        int slot = event.getSlot(); Integer map = h.maps.get(slot);
        if (map != null) {
            var c = data.get(h.collection); var photo = plugin.albumEntry(map);
            if (c == null || !c.visible(p.getUniqueId()) || !c.photos().contains(map) || photo == null || !photo.photographer().equals(c.owner())) {
                result(p, PhotoCollections.Result.DENIED); p.closeInventory(); return;
            }
            if (event.isShiftClick() && c.owner().equals(p.getUniqueId())) {
                result(p, data.photo(p.getUniqueId(), c.id(), map, photo.photographer(), false)); open(p, c.id(), h.page);
            } else plugin.reprint(p, map, true);
        } else { Consumer<Player> action = h.actions.get(slot); if (action != null) action.accept(p); }
    }
    @EventHandler public void drag(InventoryDragEvent event) { if (core.compat().inventory().top(event.getView()).getHolder() instanceof Holder) event.setCancelled(true); }
    private static final class Holder implements InventoryHolder {
        final UUID viewer; final Map<Integer, Consumer<Player>> actions = new HashMap<>(); final Map<Integer, Integer> maps = new HashMap<>();
        Inventory inventory; UUID collection; int page;
        Holder(UUID viewer) { this.viewer = viewer; }
        void set(int slot, ItemStack item, Consumer<Player> action) { inventory.setItem(slot, item); actions.put(slot, action); }
        @Override public Inventory getInventory() { return inventory; }
    }
}
