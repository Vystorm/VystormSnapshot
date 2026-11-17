package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.playermenu.PlayerMenu;
import Vystorm.vystorm_core.playermenu.PlayerMenuEntry;
import Vystorm.vystorm_core.playermenu.PlayerMenuInterface;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;

/** "Photos by …" in the Core player menu. */
final class GalleryMenuEntries {
    private GalleryMenuEntries() {}

    static void register(SnapshotPlugin plugin, PlayerMenuInterface playerMenu, Gallery gallery, Lang lang) {
        playerMenu.register(plugin, PlayerMenuEntry.builder("photos")
                .priority(220)
                .visibleWhen((viewer, target) -> plugin.galleryOn(viewer) && gallery.count(target.getUniqueId()) > 0)
                .icon((viewer, target) -> lang.lore(lang.name(new ItemStack(Material.PAINTING), viewer, "menu-photos", "player", target.getName()),
                        viewer, "menu-photos-lore", "count", Integer.toString(gallery.count(target.getUniqueId()))))
                .onClick((viewer, target, click) -> {
                    plugin.openGallery(viewer, 0, false, target.getUniqueId(), target.getName());
                    return PlayerMenu.Result.KEEP;
                })
                .build());
    }
}
