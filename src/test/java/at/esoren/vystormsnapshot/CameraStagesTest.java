package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStageTesting;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The camera click of Snapshot, called by Vystorm Core's real stage dispatcher together with the handlers of a second
 * plugin: it is an ACT handler that also gets cancelled clicks (a click into the air always arrives cancelled), runs
 * after every protection and consumes the click before any observer.
 */
class CameraStagesTest {
    private final List<String> calls = new ArrayList<>();
    private final Plugin other = EventStageTesting.plugin("AnotherPlugin");
    private final Player player = mock(Player.class);
    private final ItemStack camera = mock(ItemStack.class);
    private EventStageTesting stages;
    private SnapshotPlugin snapshot;

    @BeforeEach
    void setUp() throws Exception {
        snapshot = mock(SnapshotPlugin.class, CALLS_REAL_METHODS);
        NamespacedKey key = NamespacedKey.minecraft("camera");
        var field = SnapshotPlugin.class.getDeclaredField("cameraKey");
        field.setAccessible(true);
        field.set(snapshot, key);
        doReturn("VystormSnapshot").when(snapshot).getName();
        doReturn(true).when(snapshot).isEnabled();
        doNothing().when(snapshot).openCameraMenu(any());
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(camera.hasItemMeta()).thenReturn(true);
        when(camera.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.has(key, PersistentDataType.BYTE)).thenReturn(true);
        when(player.isSneaking()).thenReturn(true);
        when(player.hasPermission("vystormsnapshot.use")).thenReturn(true);
        stages = EventStageTesting.detached();
        // the other plugin registers first and sorts first by name: only the stage decides the order
        stages.handle(other, PlayerInteractEvent.class, EventStage.PROTECT, true, event -> calls.add("protect, menu open: " + menuOpened()));
        stages.handle(other, PlayerInteractEvent.class, EventStage.FINISH, event -> calls.add("finish"));
        stages.handle(other, PlayerInteractEvent.class, EventStage.OBSERVE, event -> calls.add("observe"));
        snapshot.handleSharedEvents(stages.stages());
    }

    private boolean menuOpened() {
        return org.mockito.Mockito.mockingDetails(snapshot).getInvocations().stream().anyMatch(call -> call.getMethod().getName().equals("openCameraMenu"));
    }

    @Test
    void givenTheCamera_whenThePlayerSneakClicksIntoTheAir_thenTheMenuOpensAlthoughTheClickArrivesCancelled() {
        PlayerInteractEvent click = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, camera, null, BlockFace.SELF, EquipmentSlot.HAND);
        assertTrue(click.isCancelled(), "Bukkit delivers air clicks cancelled");

        stages.fire(click);

        verify(snapshot).openCameraMenu(player);
        assertEquals(List.of("protect, menu open: false"), calls);
    }

    @Test
    void givenTheCamera_whenThePlayerSneakClicksABlock_thenTheClickIsConsumedAfterProtectionAndBeforeObservers() {
        PlayerInteractEvent click = stages.fire(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, camera, mock(Block.class), BlockFace.UP, EquipmentSlot.HAND));

        assertTrue(click.isCancelled());
        verify(snapshot).openCameraMenu(player);
        assertEquals(List.of("protect, menu open: false"), calls);
    }

    @Test
    void givenAnotherItem_whenThePlayerClicks_thenTheCameraStaysOutOfIt() {
        PlayerInteractEvent click = stages.fire(new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, mock(ItemStack.class), mock(Block.class), BlockFace.UP, EquipmentSlot.HAND));

        verify(snapshot, never()).openCameraMenu(any());
        assertEquals(List.of("protect, menu open: false", "finish", "observe"), calls);
        assertEquals(false, click.isCancelled());
    }
}
