package at.esoren.vystormsnapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PhotoContestTest {
    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();
    @TempDir Path folder;

    @Test
    void givenContest_whenEnteringAndVoting_thenRulesApply() {
        PhotoContest c = new PhotoContest(folder.resolve("contest.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals("closed", c.enter(1, A, "Ann", 0));
        assertTrue(c.start("Sunset", 0, 1000));
        assertFalse(c.start("Again", 0, 1000));
        assertEquals("ok", c.enter(1, A, "Ann", 10));
        assertEquals("taken", c.enter(1, B, "Ben", 10));
        assertEquals("ok", c.enter(2, B, "Ben", 20));
        assertEquals("own", c.vote(1, A, 30));
        assertEquals("voted", c.vote(2, A, 30));
        assertEquals("unvoted", c.vote(2, A, 31));
        assertEquals("missing", c.vote(9, A, 31));
        assertEquals("closed", c.vote(2, A, 1000));
        assertTrue(c.due(1000));
    }

    @Test
    void givenTie_whenClosed_thenEarlierEntryWins() {
        PhotoContest c = new PhotoContest(folder.resolve("contest.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        c.start("Snow", 0, 1000);
        c.enter(1, A, "Ann", 10);
        c.enter(2, B, "Ben", 20);
        c.vote(1, C, 30); c.vote(2, C, 30);
        PhotoContest.Result r = c.close();
        assertEquals("Ann", r.name());
        assertEquals(1, r.votes());
        assertNull(c.theme());
        assertEquals("Snow", new PhotoContest(folder.resolve("contest.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).last().orElseThrow().theme());
    }

    @Test
    void givenRunningContest_whenReloaded_thenEntriesAndVotesSurvive() {
        PhotoContest c = new PhotoContest(folder.resolve("contest.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        c.start("Night", 0, 5000);
        c.enter(7, A, "Ann", 10);
        c.vote(7, B, 20);
        PhotoContest back = new PhotoContest(folder.resolve("contest.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertTrue(back.running(100));
        assertEquals(1, back.ranking().getFirst().votes.size());
        assertEquals(7, back.ranking().getFirst().map);
    }

    @Test
    void givenNoEntries_whenClosed_thenEmptyResult() {
        PhotoContest c = new PhotoContest(folder.resolve("contest.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        c.start("Empty", 0, 10);
        assertEquals(-1, c.close().map());
        assertNull(c.close());
    }

    @Test
    void givenMinutes_whenFormatted_thenReadable() {
        assertEquals("45 min", SnapshotPlugin.duration(45));
        assertEquals("2 h 15 min", SnapshotPlugin.duration(135));
        assertEquals("3 h", SnapshotPlugin.duration(180));
    }
}
