package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GalleryTest {
    private static final UUID ALEX = UUID.randomUUID(), SAM = UUID.randomUUID(), KIM = UUID.randomUUID();
    @TempDir Path folder;

    private Gallery gallery() { return new Gallery(folder.resolve("gallery.yml"), Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT); }

    @Test
    void likesToggleAndOwnPhotosCannotBeLiked() {
        Gallery gallery = gallery();
        gallery.publish(10, ALEX, "Alex", "2026-10-30", 1000);
        assertEquals(Gallery.Like.OWN, gallery.like(10, ALEX));
        assertEquals(Gallery.Like.LIKED, gallery.like(10, SAM));
        assertEquals(1, gallery.exhibit(10).likes.size());
        assertEquals(Gallery.Like.UNLIKED, gallery.like(10, SAM));
        assertEquals(0, gallery.exhibit(10).likes.size());
        assertEquals(Gallery.Like.MISSING, gallery.like(99, SAM));
    }

    @Test
    void survivesARestart() {
        Gallery gallery = gallery();
        gallery.publish(10, ALEX, "Alex", "2026-10-30", 1000);
        gallery.publish(20, SAM, "Sam", "2026-10-30", 2000);
        gallery.like(10, SAM);
        gallery.like(10, KIM);
        assertFalse(Files.exists(folder.resolve("gallery.yml")), "changes are batched, not written per click");
        gallery.flush();
        Gallery again = gallery();
        assertTrue(again.shown(10) && again.shown(20));
        assertEquals(2, again.exhibit(10).likes.size());
        assertEquals("Sam", again.exhibit(20).name);
        assertEquals(2000, again.exhibit(20).published);
        assertTrue(again.remove(20));
        again.flush();
        assertFalse(gallery().shown(20));
        assertFalse(again.remove(20));
    }

    @Test
    void brokenEntriesAreSkipped() throws Exception {
        Files.writeString(folder.resolve("gallery.yml"), """
                exhibits:
                  '5':
                    by: not-a-uuid
                  six:
                    by: %s
                  '7':
                    by: %s
                    name: Alex
                    likes: [oops, %s]
                """.formatted(ALEX, ALEX, SAM));
        Gallery gallery = gallery();
        assertNull(gallery.exhibit(5));
        assertEquals(1, gallery.exhibit(7).likes.size());
        assertEquals(1, gallery.count(ALEX));
    }

    @Test
    void ordersNewestOrMostLikedAndFiltersByPhotographer() {
        Gallery gallery = gallery();
        gallery.publish(1, ALEX, "Alex", "", 100);
        gallery.publish(2, SAM, "Sam", "", 200);
        gallery.publish(3, ALEX, "Alex", "", 300);
        gallery.like(1, SAM);
        gallery.like(1, KIM);
        gallery.like(2, KIM);
        assertEquals(List.of(3, 2, 1), gallery.order(false, null).stream().map(e -> e.map).toList());
        assertEquals(List.of(1, 2, 3), gallery.order(true, null).stream().map(e -> e.map).toList());
        assertEquals(List.of(3, 1), gallery.order(false, ALEX).stream().map(e -> e.map).toList());
        assertEquals(2, gallery.count(ALEX));
        assertEquals(1, Gallery.pages(0));
        assertEquals(1, Gallery.pages(45));
        assertEquals(2, Gallery.pages(46));
    }

    @Test
    void anUnreadableFileIsMovedAsideAndNeverOverwritten() throws Exception {
        String content = "exhibits:\n  '10':\n    by: %s\n    likes: [\n".formatted(ALEX);
        Files.writeString(folder.resolve("gallery.yml"), content);
        Gallery gallery = gallery();
        assertTrue(gallery.broken() != null && gallery.broken().startsWith("gallery.yml.broken-"));
        assertEquals(content, Files.readString(folder.resolve(gallery.broken())), "the original is kept unchanged");
        gallery.publish(20, SAM, "Sam", "", 1);
        assertEquals(Gallery.Like.MISSING, gallery.like(20, ALEX));
        assertFalse(gallery.remove(20));
        gallery.flush();
        assertFalse(Files.exists(folder.resolve("gallery.yml")), "a read-only gallery writes nothing");
    }

    @Test
    void flushWritesOnlyWhenSomethingChanged() throws Exception {
        Gallery gallery = gallery();
        gallery.flush();
        assertFalse(Files.exists(folder.resolve("gallery.yml")));
        gallery.publish(10, ALEX, "Alex", "", 1);
        gallery.flush();
        Files.delete(folder.resolve("gallery.yml"));
        gallery.flush();
        assertFalse(Files.exists(folder.resolve("gallery.yml")), "nothing new, nothing written");
        gallery.like(10, SAM);
        gallery.flush();
        assertEquals(1, gallery().exhibit(10).likes.size());
    }
}
