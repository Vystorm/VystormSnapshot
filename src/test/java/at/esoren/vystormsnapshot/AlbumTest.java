package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AlbumTest {
    private static final UUID ALEX = UUID.randomUUID(), SAM = UUID.randomUUID();

    private static Album.Entry entry(int map, UUID by, String part) {
        return new Album.Entry(map, by, "x", "world", "", "2026-10-29", part);
    }

    @Test
    void worldFilterKeepsOnlyOwnPhotosNewestFirstAfterReload(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) {
        Album album = new Album(folder.resolve("album.yml"), java.util.logging.Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        album.add(entry(1, ALEX, ""));
        album.add(new Album.Entry(2, ALEX, "Alex", "old_nether", "", "2026-11-02", "1/2"));
        album.add(new Album.Entry(3, ALEX, "Alex", "old_nether", "", "2026-11-02", "2/2"));
        album.add(new Album.Entry(4, SAM, "Sam", "old_nether", "", "2026-11-02", ""));
        album = new Album(folder.resolve("album.yml"), java.util.logging.Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        assertEquals(List.of(3, 2), album.of(ALEX, "OLD_NETHER").stream().map(Album.Entry::map).toList());
        assertEquals(List.of(3, 2, 1), album.of(ALEX).stream().map(Album.Entry::map).toList());
        assertEquals(List.of(2, 3), album.set(3), "filtering must not split a big photo");
        assertEquals(List.of(), album.of(ALEX, "missing"));
        assertEquals(List.of(), album.of(UUID.randomUUID(), "old_nether"));
    }

    @Test
    void worldSuggestionsAreUniqueSortedAndPrivate(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) {
        Album album = new Album(folder.resolve("album.yml"), java.util.logging.Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        album.add(entry(1, ALEX, ""));
        album.add(entry(2, ALEX, ""));
        album.add(new Album.Entry(3, ALEX, "Alex", "archived", "", "", ""));
        album.add(new Album.Entry(4, SAM, "Sam", "private", "", "", ""));
        album.add(new Album.Entry(5, ALEX, "Alex", "", "", "", ""));
        assertEquals(List.of("archived", "world"), album.worlds(ALEX));
        assertEquals(List.of(), album.worlds(UUID.randomUUID()));
    }

    @Test
    void captionSearchIsLiteralPrivateAndKeepsNewestFirstAfterReload(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) {
        Album album = new Album(folder.resolve("album.yml"), java.util.logging.Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        album.add(new Album.Entry(1, ALEX, "Alex", "world", "", "", "", "Our Castle"));
        album.add(new Album.Entry(2, SAM, "Sam", "world", "", "", "", "Our Castle"));
        album.add(new Album.Entry(3, ALEX, "Alex", "nether", "", "", "", "Castle [rare]"));
        album.add(new Album.Entry(4, ALEX, "Alex", "world", "", "", "", "城堡"));
        album = new Album(folder.resolve("album.yml"), java.util.logging.Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        assertEquals(List.of(3, 1), album.of(ALEX, null, " CASTLE ").stream().map(Album.Entry::map).toList());
        assertEquals(List.of(1), album.of(ALEX, "world", "castle").stream().map(Album.Entry::map).toList());
        assertEquals(List.of(3), album.of(ALEX, null, "[rare]").stream().map(Album.Entry::map).toList());
        assertEquals(List.of(4), album.of(ALEX, null, "城堡").stream().map(Album.Entry::map).toList());
        assertEquals(List.of(), album.of(ALEX, null, ".*"));
        assertEquals(List.of(), album.of(UUID.randomUUID(), null, "castle"));
    }

    @Test
    void pages() {
        assertEquals(1, Album.pages(0));
        assertEquals(1, Album.pages(54));
        assertEquals(54, Album.perPage(54));
        assertEquals(2, Album.pages(55));
        assertEquals(45, Album.perPage(55));
        assertEquals(2, Album.pages(90));
        assertEquals(3, Album.pages(91));
    }

    @Test
    void findsTheWholeBigPhoto() {
        Map<Integer, Album.Entry> album = new HashMap<>();
        for (int i = 1; i <= 4; i++) album.put(9 + i, entry(9 + i, ALEX, i + "/4"));
        assertEquals(List.of(10, 11, 12, 13), Album.set(album.get(12), album::get));
        assertEquals(List.of(10, 11, 12, 13), Album.set(album.get(10), album::get));
    }

    @Test
    void singleOrBrokenSetsReprintOnlyTheClickedMap() {
        Map<Integer, Album.Entry> album = new HashMap<>();
        album.put(5, entry(5, ALEX, ""));
        assertEquals(List.of(5), Album.set(album.get(5), album::get));
        album.put(20, entry(20, ALEX, "1/2"));
        album.put(21, entry(21, SAM, "2/2"));
        assertEquals(List.of(20), Album.set(album.get(20), album::get), "other photographer");
        album.put(30, entry(30, ALEX, "1/3"));
        album.put(31, entry(31, ALEX, "2/3"));
        assertEquals(List.of(31), Album.set(album.get(31), album::get), "missing part");
        album.put(40, entry(40, ALEX, "oops/2"));
        assertEquals(List.of(40), Album.set(album.get(40), album::get));
    }

    @Test
    void anUnreadableFileIsMovedAsideAndNeverOverwritten(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        String content = "photos:\n  '10':\n    by: %s\n    name: [\n".formatted(ALEX);
        java.nio.file.Files.writeString(folder.resolve("album.yml"), content);
        Album album = new Album(folder.resolve("album.yml"), java.util.logging.Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        org.junit.jupiter.api.Assertions.assertTrue(album.broken() != null && album.broken().startsWith("album.yml.broken-"));
        assertEquals(content, java.nio.file.Files.readString(folder.resolve(album.broken())), "the original is kept unchanged");
        album.add(entry(11, SAM, ""));
        org.junit.jupiter.api.Assertions.assertFalse(java.nio.file.Files.exists(folder.resolve("album.yml")), "no empty album is written over it");
        assertEquals(SAM, album.entry(11).photographer());
    }

    @Test
    void aReadableAlbumIsSavedAndLoaded(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) {
        Album album = new Album(folder.resolve("album.yml"), java.util.logging.Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        album.add(entry(7, ALEX, "1/2"));
        Album again = new Album(folder.resolve("album.yml"), java.util.logging.Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        org.junit.jupiter.api.Assertions.assertNull(again.broken());
        assertEquals("1/2", again.entry(7).part());
    }
}
