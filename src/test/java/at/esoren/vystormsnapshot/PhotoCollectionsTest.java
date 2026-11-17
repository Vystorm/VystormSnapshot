package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PhotoCollectionsTest {
    @TempDir Path folder;
    private final UUID owner = UUID.randomUUID(), reader = UUID.randomUUID(), stranger = UUID.randomUUID();
    private PhotoCollections open() { return new PhotoCollections(folder.resolve("collections.yml"), Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT); }
    @Test void privateCollectionsAndExplicitSharingSurviveRestart() {
        var data = open(); assertEquals(PhotoCollections.Result.SAVED, data.create(owner, "holiday"));
        var c = data.own(owner, "HOLIDAY");
        assertEquals(PhotoCollections.Result.SAVED, data.title(owner, c.id(), "Winter trip 冬"));
        assertEquals(PhotoCollections.Result.SAVED, data.photo(owner, c.id(), 123, owner, true));
        assertEquals(PhotoCollections.Result.SAVED, data.share(owner, c.id(), reader, true));
        data = open(); c = data.own(owner, "holiday");
        assertEquals("Winter trip 冬", c.title()); assertEquals(List.of(123), c.photos());
        assertEquals(1, data.visible(reader).size()); assertTrue(data.visible(stranger).isEmpty());
        assertEquals(PhotoCollections.Result.SAVED, data.share(owner, c.id(), reader, false));
        assertTrue(data.visible(reader).isEmpty()); assertFalse(open().get(c.id()).visible(reader));
    }
    @Test void readersCannotEditShareDeleteOrAddAnotherPhotographersPhoto() {
        var data = open(); data.create(owner, "trip"); var c = data.own(owner, "trip"); data.share(owner, c.id(), reader, true);
        assertEquals(PhotoCollections.Result.DENIED, data.title(reader, c.id(), "stolen"));
        assertEquals(PhotoCollections.Result.DENIED, data.share(reader, c.id(), stranger, true));
        assertEquals(PhotoCollections.Result.DENIED, data.delete(reader, c.id()));
        assertEquals(PhotoCollections.Result.DENIED, data.photo(owner, c.id(), 5, stranger, true));
        assertEquals(PhotoCollections.Result.DENIED, data.photo(reader, c.id(), 6, reader, true));
        assertTrue(data.get(c.id()).photos().isEmpty());
    }
    @Test void removingOrDeletingCollectionDoesNotModifyOtherAlbums() {
        var data = open(); data.create(owner, "a"); data.create(owner, "b");
        var a = data.own(owner, "a"); var b = data.own(owner, "b");
        data.photo(owner, a.id(), 8, owner, true); data.photo(owner, b.id(), 8, owner, true);
        data.photo(owner, a.id(), 8, owner, false); assertEquals(List.of(8), data.get(b.id()).photos());
        data.delete(owner, a.id()); assertNull(open().get(a.id())); assertEquals(List.of(8), open().get(b.id()).photos());
    }
    @Test void corruptedFileRemainsUntouchedAndCannotBeOverwritten() throws Exception {
        Path file = folder.resolve("collections.yml"); String corrupt = "collections: [this-is-not-a-section]"; Files.writeString(file, corrupt);
        var data = open(); assertEquals(PhotoCollections.Result.UNAVAILABLE, data.create(owner, "new"));
        assertEquals(corrupt, Files.readString(file));
    }
    @Test void failedWriteDoesNotGrantReadAccessInMemory() throws Exception {
        var data = open(); data.create(owner, "a"); var c = data.own(owner, "a");
        Path file = folder.resolve("collections.yml"); Files.delete(file); Files.createDirectory(file); Files.writeString(file.resolve("keep"), "occupied");
        assertEquals(PhotoCollections.Result.UNAVAILABLE, data.share(owner, c.id(), reader, true));
        assertFalse(data.get(c.id()).visible(reader));
    }
    @Test void limitsAndNamesCannotBeBypassedWithCaseOrPathCharacters() {
        var data = open(); assertEquals(PhotoCollections.Result.INVALID, data.create(owner, "../x"));
        assertEquals(PhotoCollections.Result.SAVED, data.create(owner, "Trip"));
        assertEquals(PhotoCollections.Result.INVALID, data.create(owner, "trip"));
        for (int n = 1; n < 20; n++) assertEquals(PhotoCollections.Result.SAVED, data.create(owner, "a" + n));
        assertEquals(PhotoCollections.Result.LIMIT, data.create(owner, "overflow"));
        var c = data.own(owner, "Trip"); assertEquals(PhotoCollections.Result.INVALID, data.title(owner, c.id(), "bad\nname"));
        assertThrows(UnsupportedOperationException.class, () -> c.photos().add(4));
    }
    @Test void duplicatePhotoDoesNotConsumeCapacityAndSharedPlayersAreBounded() {
        var data = open(); data.create(owner, "a"); var c = data.own(owner, "a");
        data.photo(owner, c.id(), 10, owner, true); data.photo(owner, c.id(), 10, owner, true);
        assertEquals(List.of(10), data.get(c.id()).photos());
        for (int n = 0; n < 32; n++) assertEquals(PhotoCollections.Result.SAVED, data.share(owner, c.id(), UUID.randomUUID(), true));
        assertEquals(PhotoCollections.Result.LIMIT, data.share(owner, c.id(), stranger, true));
    }
}
