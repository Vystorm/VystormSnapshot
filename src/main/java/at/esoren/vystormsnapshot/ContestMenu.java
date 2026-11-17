package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core.compat.InventoryCompatInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
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
 * Chest menus of the photo contest: the entries (click = vote / take the vote back) with an "enter a photo" button,
 * and the picker with the player's own gallery photos (click = enter that one).
 */
final class ContestMenu {
    private ContestMenu() {}

    static final class Holder implements InventoryHolder {
        final boolean picker;
        final List<Integer> maps = new ArrayList<>();
        Inventory inventory;
        Holder(boolean picker) { this.picker = picker; }
        @Override public Inventory getInventory() { return inventory; }
    }

    static Inventory entries(PlatformCompatInterface platform, SnapshotPlugin plugin, Lang lang, Player viewer, PhotoContest contest) {
        Holder holder = new Holder(false);
        long now = System.currentTimeMillis();
        long minutes = Math.max(0, (contest.ends() - now + 59_999) / 60_000);
        Inventory inventory = platform.createInventory(holder, 54,
                lang.component(viewer, "contest.title", "theme", contest.theme(), "time", SnapshotPlugin.duration(minutes)));
        holder.inventory = inventory;
        for (PhotoContest.Entry entry : contest.ranking()) {
            if (holder.maps.size() >= 45) break;
            MapView view = Bukkit.getMap(entry.map);
            if (view == null) continue;
            ItemStack icon = Album.icon(platform, view, plugin.albumEntry(entry.map), lang, viewer);
            boolean voted = entry.votes.contains(viewer.getUniqueId()), own = entry.photographer.equals(viewer.getUniqueId());
            platform.editMeta(icon, meta -> {
                List<Component> lore = platform.lore(meta) == null ? new ArrayList<>() : new ArrayList<>(platform.lore(meta));
                lore.add(lang.component(viewer, "contest.entry-by", "player", entry.name, "votes", entry.votes.size()));
                lore.add(lang.component(viewer, own ? "contest.entry-own" : voted ? "contest.entry-unvote" : "contest.entry-vote"));
                platform.lore(meta, lore.stream().map(l -> l.decoration(TextDecoration.ITALIC, false)).toList());
            });
            inventory.setItem(holder.maps.size(), icon);
            holder.maps.add(entry.map);
        }
        ItemStack enter = new ItemStack(Material.WRITABLE_BOOK);
        platform.editMeta(enter, meta -> {
            platform.displayName(meta, lang.component(viewer, "contest.enter-button").decoration(TextDecoration.ITALIC, false));
            platform.lore(meta, List.of(lang.component(viewer, "contest.enter-hint").decoration(TextDecoration.ITALIC, false)));
        });
        inventory.setItem(49, enter);
        return inventory;
    }

    static Inventory picker(PlatformCompatInterface platform, SnapshotPlugin plugin, Lang lang, Player viewer, List<Integer> ownExhibits) {
        Holder holder = new Holder(true);
        Inventory inventory = platform.createInventory(holder, 54, lang.component(viewer, "contest.picker-title"));
        holder.inventory = inventory;
        for (int map : ownExhibits) {
            if (holder.maps.size() >= 45) break;
            MapView view = Bukkit.getMap(map);
            if (view == null) continue;
            inventory.setItem(holder.maps.size(), Album.icon(platform, view, plugin.albumEntry(map), lang, viewer));
            holder.maps.add(map);
        }
        inventory.setItem(49, Album.arrow(platform, lang.component(viewer, "contest.back")));
        return inventory;
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
            if (slot >= 0 && slot < holder.maps.size()) {
                if (holder.picker) plugin.contestEnter(player, holder.maps.get(slot));
                else plugin.contestVote(player, holder.maps.get(slot));
            } else if (slot == 49) {
                if (holder.picker) plugin.openContest(player);
                else plugin.openContestPicker(player);
            }
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (inventories.top(event.getView()).getHolder() instanceof Holder) event.setCancelled(true);
        }
    }
}
