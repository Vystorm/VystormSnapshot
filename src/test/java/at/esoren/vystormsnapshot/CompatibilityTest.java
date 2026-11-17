package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.compat.InventoryCompat;
import Vystorm.vystorm_core.compat.ServerCompat;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.junit.jupiter.api.Test;

/** The same compiled tests run with both the current API and the 1.20.6 class-based InventoryView. */
class CompatibilityTest {
    @Test void materialSelectionUsesAvailableMaterialOrFallback() {
        assertEquals(Material.PAPER, ServerCompat.material("PAPER", Material.SPYGLASS));
        assertEquals(Material.SPYGLASS, ServerCompat.material("NOT_A_REAL_MATERIAL", Material.SPYGLASS));
        assertEquals(Material.PAPER, ServerCompat.material("NOT_A_REAL_MATERIAL", Material.PAPER));
    }

    @Test void featureDetectionUsesClasses() {
        assertTrue(ServerCompat.hasClass("org.bukkit.inventory.InventoryView"));
        assertFalse(ServerCompat.hasClass("at.esoren.vystormsnapshot.MissingFeature"));
    }

    @Test void cameraMenuWorksWithBothInventoryViewShapes() throws Exception { menu(CameraMenu.class); }
    @Test void albumWorksWithBothInventoryViewShapes() throws Exception { menu(Album.class); }
    @Test void galleryWorksWithBothInventoryViewShapes() throws Exception { menu(Gallery.class); }

    private void menu(Class<?> menu) throws Exception {
        var holderConstructor = Class.forName(menu.getName() + "$Holder").getDeclaredConstructor();
        holderConstructor.setAccessible(true);
        InventoryHolder holder = (InventoryHolder) holderConstructor.newInstance();
        Inventory top = mock(Inventory.class);
        when(top.getHolder()).thenReturn(holder);
        InventoryView view = mock(InventoryView.class);
        // Reflective stubbing avoids embedding either invokevirtual or invokeinterface for the changed API.
        when((Inventory) InventoryView.class.getMethod("getTopInventory").invoke(view)).thenReturn(top);
        assertSame(top, InventoryCompat.top(view));

        SnapshotPlugin plugin = mock(SnapshotPlugin.class);
        Class<?> clicks = Class.forName(menu.getName() + "$Clicks");
        var constructor = clicks.getDeclaredConstructor(SnapshotPlugin.class, Vystorm.vystorm_core.compat.InventoryCompatInterface.class);
        constructor.setAccessible(true);
        Object listener = constructor.newInstance(plugin, Vystorm.vystorm_core.compat.InventoryCompatInterface.DEFAULT);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getView()).thenReturn(view);
        when(click.getClickedInventory()).thenReturn(top);
        when(click.getWhoClicked()).thenReturn(mock(Player.class));
        when(click.getSlot()).thenReturn(-1);
        clicks.getMethod("onClick", InventoryClickEvent.class).invoke(listener, click);
        verify(click).setCancelled(true);

        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);
        clicks.getMethod("onDrag", InventoryDragEvent.class).invoke(listener, drag);
        verify(drag).setCancelled(true);
    }
}
