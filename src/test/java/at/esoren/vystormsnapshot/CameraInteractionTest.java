package at.esoren.vystormsnapshot;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

class CameraInteractionTest {
    @Test void sneakRightClickOpensMenuForEitherTriggerWithoutTakingPhoto() throws Exception {
        for(boolean left:new boolean[]{true,false}) for(Action action:new Action[]{Action.RIGHT_CLICK_AIR,Action.RIGHT_CLICK_BLOCK}) {
            var plugin=mock(SnapshotPlugin.class,CALLS_REAL_METHODS);
            var key=NamespacedKey.minecraft("camera");
            var field=SnapshotPlugin.class.getDeclaredField("cameraKey");field.setAccessible(true);field.set(plugin,key);
            field=SnapshotPlugin.class.getDeclaredField("leftClick");field.setAccessible(true);field.set(plugin,left);
            var player=mock(Player.class);when(player.isSneaking()).thenReturn(true);
            when(player.hasPermission("vystormsnapshot.use")).thenReturn(true);
            doNothing().when(plugin).openCameraMenu(player);
            var item=mock(ItemStack.class);var meta=mock(ItemMeta.class);var data=mock(PersistentDataContainer.class);
            when(item.hasItemMeta()).thenReturn(true);when(item.getItemMeta()).thenReturn(meta);
            when(meta.getPersistentDataContainer()).thenReturn(data);when(data.has(key,PersistentDataType.BYTE)).thenReturn(true);
            var event=mock(PlayerInteractEvent.class);when(event.getHand()).thenReturn(EquipmentSlot.HAND);
            when(event.getItem()).thenReturn(item);when(event.getPlayer()).thenReturn(player);when(event.getAction()).thenReturn(action);
            doReturn("VystormSnapshot").when(plugin).getName();doReturn(true).when(plugin).isEnabled();
            var stages=Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting.detached();
            plugin.handleSharedEvents(stages.stages());
            stages.fire(event);
            verify(plugin).openCameraMenu(player);verify(event).setCancelled(true);
            verify(player,never()).getInventory();
            clearInvocations(plugin,event);
            when(event.getHand()).thenReturn(EquipmentSlot.OFF_HAND);stages.fire(event);
            verify(plugin,never()).openCameraMenu(any());verify(event,never()).setCancelled(anyBoolean());
        }
    }
}
