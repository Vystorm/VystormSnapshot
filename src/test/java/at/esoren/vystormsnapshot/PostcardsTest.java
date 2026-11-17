package at.esoren.vystormsnapshot;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PostcardsTest {
    private static final UUID FROM = UUID.randomUUID(), TO = UUID.randomUUID();

    @Test
    void givenWaitingCards_whenReloaded_thenTheyArriveOldestFirst(@TempDir Path dir) {
        Path file = dir.resolve("postcards.yml");
        Postcards cards = new Postcards(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertTrue(cards.keep(TO, new Postcards.Card(7, FROM, "Alex", "Greetings from the coast", 1)));
        assertTrue(cards.keep(TO, new Postcards.Card(8, FROM, "Alex", "", 2)));
        Postcards back = new Postcards(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertEquals(2, back.pending(TO));
        List<Postcards.Card> first = back.take(TO, 1);
        assertEquals(7, first.getFirst().map());
        assertEquals("Greetings from the coast", first.getFirst().text());
        assertEquals(1, new Postcards(file, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT).pending(TO));
    }

    @Test
    void givenLimits_whenChecked_thenDailyCapAndTextRulesHold() {
        long now = 100_000_000L;
        assertTrue(Postcards.allowed(List.of(now - 1, now - 2), now, 3));
        assertFalse(Postcards.allowed(List.of(now - 1, now - 2, now - 3), now, 3));
        assertTrue(Postcards.allowed(List.of(now - 90_000_000L, now - 1, now - 2), now, 3), "older than a day does not count");
        assertEquals("Hi there", Postcards.clean("  Hi \u00a7c  there "));
        assertNull(Postcards.clean("x".repeat(Postcards.MAX_TEXT + 1)));
    }

    @Test
    void givenFullMailbox_whenKept_thenRefused(@TempDir Path dir) {
        Postcards cards = new Postcards(dir.resolve("p.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        for (int i = 0; i < Postcards.MAX_PENDING; i++) assertTrue(cards.keep(TO, new Postcards.Card(i, FROM, "A", "", i)));
        assertFalse(cards.keep(TO, new Postcards.Card(99, FROM, "A", "", 99)));
    }
}
