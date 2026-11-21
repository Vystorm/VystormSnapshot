package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.compat.CompatInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.junit.jupiter.api.Test;

/**
 * Spigot has no {@code Bukkit.getOfflinePlayerIfCached} (Paper API): {@code /snapshot postcard} called it directly and
 * failed there with NoSuchMethodError. The name lookup goes through Core's platform part, which has a Spigot fallback.
 * Without a server, a direct Bukkit call fails here too (no Bukkit server), so this test also shows the old path.
 */
class PostcardPlatformTest {
    @Test
    void givenAHeldPhoto_whenThePostcardNamesAnUnknownPlayer_thenTheLookupGoesThroughCoresPlatformPart() throws Exception {
        SnapshotPlugin plugin = mock(SnapshotPlugin.class, CALLS_REAL_METHODS);
        Path folder = Files.createTempDirectory("snapshot-postcard");
        Album album = new Album(folder.resolve("album.yml"), java.util.logging.Logger.getAnonymousLogger(),
                Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, PlatformCompatInterface.DEFAULT);
        album.add(new Album.Entry(7, UUID.randomUUID(), "Alex", "world", "", "2026-11-21", ""));
        Lang lang = mock(Lang.class);
        PlatformCompatInterface platform = mock(PlatformCompatInterface.class);
        when(platform.getOfflinePlayerIfCached("Bob")).thenReturn(null);
        CompatInterface compat = mock(CompatInterface.class);
        when(compat.platform()).thenReturn(platform);
        API core = mock(API.class);
        when(core.compat()).thenReturn(compat);
        set(plugin, "album", album);
        set(plugin, "lang", lang);
        set(plugin, "core", core);
        doReturn(new YamlConfiguration()).when(plugin).getConfig();

        MapView view = mock(MapView.class);
        when(view.getId()).thenReturn(7);
        MapMeta meta = mock(MapMeta.class);
        when(meta.hasMapView()).thenReturn(true);
        when(meta.getMapView()).thenReturn(view);
        ItemStack photo = mock(ItemStack.class);
        when(photo.getItemMeta()).thenReturn(meta);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getItemInMainHand()).thenReturn(photo);
        Player sender = mock(Player.class);
        when(sender.getInventory()).thenReturn(inventory);

        assertDoesNotThrow(() -> plugin.postcard(sender, "Bob", null));
        verify(platform).getOfflinePlayerIfCached("Bob");
        verify(lang).send(sender, "postcard.unknown", "player", "Bob");
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = SnapshotPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
