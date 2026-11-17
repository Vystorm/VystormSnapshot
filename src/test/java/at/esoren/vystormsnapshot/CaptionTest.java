package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptionTest {
    @TempDir Path folder;
    private final UUID owner = UUID.randomUUID();

    private Album album() { return new Album(folder.resolve("album.yml"), Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT); }

    private Album photo() {
        Album album = album();
        album.add(new Album.Entry(10, owner, "Alex", "world", "1 2 3", "2026-11-03", "1/2"));
        album.add(new Album.Entry(11, owner, "Alex", "world", "1 2 3", "2026-11-03", "2/2"));
        return album;
    }

    @Test void wholePhotoSurvivesRestartAndCanBeClearedFromEitherTile() {
        Album album = photo();
        assertEquals(Album.Caption.SAVED, album.caption(11, owner, "  Unser Schloss 城堡  ", 80));
        album = album();
        assertEquals("Unser Schloss 城堡", album.entry(10).caption());
        assertEquals(album.entry(10).caption(), album.entry(11).caption());
        assertEquals("1 2 3", album.entry(10).where());
        assertEquals(List.of(11, 10), album.of(owner).stream().map(Album.Entry::map).toList());
        assertEquals(Album.Caption.SAVED, album.caption(10, owner, null, 80));
        assertEquals("", album().entry(11).caption());
    }

    @Test void onlyThePhotographerCanChangeKnownPhotos() {
        Album album = photo();
        assertEquals(Album.Caption.NOT_OWNER, album.caption(10, UUID.randomUUID(), "stolen", 80));
        assertEquals(Album.Caption.MISSING, album.caption(999, owner, "missing", 80));
        assertEquals("", album().entry(10).caption());
    }

    @Test void limitsCountUnicodeCharactersAndRejectFormattingAndControls() {
        Album album = photo();
        assertEquals(Album.Caption.SAVED, album.caption(10, owner, "城📷", 2));
        for (String invalid : List.of("abc", "a\nb", "a\tb", "§a", "a\u2028b"))
            assertEquals(Album.Caption.INVALID, album.caption(10, owner, invalid, 2), invalid);
        assertEquals("城📷", album().entry(10).caption());
    }

    @Test void oldAlbumsLoadWithEmptyCaptionsAndMarkupIsStoredLiterally() {
        Album album = photo();
        assertEquals("", album().entry(10).caption());
        assertEquals(Album.Caption.SAVED, album.caption(10, owner, "<red>Not a colour</red>", 80));
        assertEquals("<red>Not a colour</red>", album().entry(10).caption());
    }

    @Test void sharedAlbumGalleryAndPrintIconRendersCaptionAsLiteralText() {
        Album album = photo();
        album.caption(10, owner, "<red>城堡</red>", 80);
        Lang lang = Lang.create("VystormSnapshot", Map.of("en", "photo-caption: '<gray>Caption: '\nphoto-lore: 'By {player}'\nphoto-where: '{where}'\nphoto-name-part: 'Photo {part}'\n"));
        MapMeta meta = mock(MapMeta.class);
        try (var items = mockConstruction(ItemStack.class, (item, context) -> when(item.getItemMeta()).thenReturn(meta))) {
            Album.icon(Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT, mock(MapView.class), album.entry(10), lang, null);
            verify(meta).lore(org.mockito.ArgumentMatchers.<List<Component>>argThat(lore ->
                    PlainTextComponentSerializer.plainText().serialize(lore.getFirst()).equals("Caption: <red>城堡</red>")));
        }
    }

    @Test void brokenAlbumIsNeverOverwritten() throws Exception {
        Files.writeString(folder.resolve("album.yml"), "photos: [");
        Album album = album();
        assertEquals(Album.Caption.UNAVAILABLE, album.caption(10, owner, "text", 80));
        assertFalse(Files.exists(folder.resolve("album.yml")));
        assertEquals("photos: [", Files.readString(folder.resolve(album.broken())));
    }

    @Test void failedWriteRollsBackAllTiles() throws Exception {
        Album album = photo();
        Files.delete(folder.resolve("album.yml"));
        Files.createDirectory(folder.resolve("album.yml"));
        Files.writeString(folder.resolve("album.yml/blocker"), "keep");
        assertEquals(Album.Caption.UNAVAILABLE, album.caption(11, owner, "text", 80));
        assertEquals("", album.entry(10).caption());
        assertEquals("", album.entry(11).caption());
    }

    @Test void incompleteSetNeverChangesAnotherPhotographersPhoto() {
        Album album = photo();
        album.add(new Album.Entry(11, UUID.randomUUID(), "Sam", "world", "", "", "2/2"));
        assertEquals(Album.Caption.SAVED, album.caption(10, owner, "mine", 80));
        assertEquals("", album().entry(11).caption());
    }

    @Test void configMigrationAddsDefaultEvenWithBundledDefaultsAndPreservesSettings() {
        YamlConfiguration config = new YamlConfiguration();
        config.addDefault("captions.max-length", 80);
        config.set("film", "DIAMOND");
        assertTrue(SnapshotPlugin.captionDefaults(config));
        assertTrue(config.contains("captions.max-length", true));
        assertEquals(80, config.getInt("captions.max-length"));
        config.set("captions.max-length", 30);
        assertFalse(SnapshotPlugin.captionDefaults(config));
        assertEquals(30, config.getInt("captions.max-length"));
        assertEquals("DIAMOND", config.getString("film"));
    }
}
