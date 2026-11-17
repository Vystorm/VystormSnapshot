package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core.compat.InventoryCompatInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

/**
 * Camera settings in one menu (swap-hand key or {@code /snapshot camera}): film style, photo format and frame of the
 * camera in hand, plus shortcuts to the album and the gallery. Settings live on the camera item, like the format.
 */
final class CameraMenu {
    private CameraMenu() {}

    static final int ALBUM_SLOT = 34, GALLERY_SLOT = 35;

    /** Slot → setting ("style:sepia", "format:2x1", "frame:white", "album", "gallery"). */
    static Map<Integer, String> layout(List<Style> styles, List<String> formats, List<Style.Frame> frames, boolean gallery) {
        return layout(styles, formats, frames, List.of(0), gallery);
    }

    /** With self-timer choices in slots 30 to 32 (shown when there is more than one). */
    static Map<Integer, String> layout(List<Style> styles, List<String> formats, List<Style.Frame> frames, List<Integer> timers, boolean gallery) {
        Map<Integer, String> slots = new HashMap<>();
        for (int i = 0; i < styles.size() && i < 9; i++) slots.put(i, "style:" + styles.get(i).id);
        if (formats.size() > 1) for (int i = 0; i < formats.size() && i < 18; i++) slots.put(9 + i, "format:" + formats.get(i));
        if (frames.size() > 1) for (int i = 0; i < frames.size() && i < 3; i++) slots.put(27 + i, "frame:" + frames.get(i).id);
        if (timers.size() > 1) for (int i = 0; i < timers.size() && i < 3; i++) slots.put(30 + i, "timer:" + timers.get(i));
        slots.put(ALBUM_SLOT, "album");
        if (gallery) slots.put(GALLERY_SLOT, "gallery");
        return slots;
    }

    static Inventory open(PlatformCompatInterface platform, SnapshotPlugin plugin, Lang lang, Player player, ItemStack camera) {
        Holder holder = new Holder();
        holder.slots.putAll(layout(plugin.styles(), plugin.formats(), plugin.frames(), plugin.timers(), plugin.galleryOn(player)));
        Inventory inventory = platform.createInventory(holder, 36, lang.component(player, "camera-menu-title"));
        holder.inventory = inventory;
        Style style = plugin.styleOf(camera);
        String format = plugin.formatOf(camera);
        Style.Frame frame = plugin.frameOf(camera);
        int timer = plugin.timerOf(camera);
        for (var slot : holder.slots.entrySet()) {
            String[] setting = slot.getValue().split(":", 2);
            ItemStack icon = switch (setting[0]) {
                case "style" -> {
                    Style s = Style.parse(setting[1]);
                    yield button(platform, new ItemStack(s.icon), lang, player, "style-" + s.id, s == style);
                }
                case "format" -> {
                    int[] size = Format.parse(setting[1]);
                    ItemStack map = new ItemStack(Material.MAP, size[0] * size[1]);
                    platform.editMeta(map, meta -> platform.displayName(meta, lang.component(player, "camera-menu-format", "format", setting[1], "maps",
                            Integer.toString(size[0] * size[1])).decoration(TextDecoration.ITALIC, false)));
                    yield mark(platform, map, setting[1].equals(format));
                }
                case "frame" -> {
                    Style.Frame f = Style.Frame.parse(setting[1]);
                    yield button(platform, new ItemStack(f.icon), lang, player, "frame-" + f.id, f == frame);
                }
                case "timer" -> {
                    int seconds = Integer.parseInt(setting[1]);
                    ItemStack clock = new ItemStack(seconds == 0 ? Material.BARRIER : Material.CLOCK, Math.max(1, seconds));
                    platform.editMeta(clock, meta -> platform.displayName(meta, (seconds == 0 ? lang.component(player, "camera-menu-timer-off")
                            : lang.component(player, "camera-menu-timer", "seconds", Integer.toString(seconds))).decoration(TextDecoration.ITALIC, false)));
                    yield mark(platform, clock, seconds == timer);
                }
                case "album" -> button(platform, new ItemStack(Material.BOOK), lang, player, "camera-menu-album", false);
                default -> button(platform, new ItemStack(Material.PAINTING), lang, player, "camera-menu-gallery", false);
            };
            inventory.setItem(slot.getKey(), icon);
        }
        return inventory;
    }

    private static ItemStack button(PlatformCompatInterface platform, ItemStack item, Lang lang, Player player, String key, boolean chosen) {
        platform.editMeta(item, meta -> platform.displayName(meta, lang.component(player, key).decoration(TextDecoration.ITALIC, false)));
        return mark(platform, item, chosen);
    }

    /** The chosen setting glints. */
    private static ItemStack mark(PlatformCompatInterface platform, ItemStack item, boolean chosen) {
        if (chosen) platform.editMeta(item, meta -> meta.setEnchantmentGlintOverride(true));
        return item;
    }

    private static final class Holder implements InventoryHolder {
        private final Map<Integer, String> slots = new HashMap<>();
        private Inventory inventory;

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
            String setting = holder.slots.get(event.getSlot());
            if (setting != null) plugin.cameraSetting(player, setting);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            if (inventories.top(event.getView()).getHolder() instanceof Holder) event.setCancelled(true);
        }
    }
}
