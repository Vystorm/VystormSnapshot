package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyByte;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapView;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Stored photos are read when a player first sees them, not all at start, and the pixels are let go after drawing. */
class LazyPhotoTest {
    @TempDir Path folder;

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void storedPhotoIsReadOnFirstRenderOnly() {
        AtomicInteger reads = new AtomicInteger();
        PhotoRenderer renderer = new PhotoRenderer(() -> { reads.incrementAndGet(); return new byte[Optics.SIZE * Optics.SIZE]; });
        assertEquals(0, reads.get(), "attaching a stored photo must not read it");
        MapCanvas canvas = mock(MapCanvas.class);
        renderer.render(mock(MapView.class), canvas, null);
        renderer.render(mock(MapView.class), canvas, null);
        assertEquals(1, reads.get());
        verify(canvas, times(Optics.SIZE * Optics.SIZE)).setPixel(anyInt(), anyInt(), anyByte());
    }

    @Test
    @SuppressWarnings({"deprecation", "removal"})
    void unreadablePhotoLeavesTheCanvasAloneAndIsNotRetried() {
        AtomicInteger reads = new AtomicInteger();
        PhotoRenderer renderer = new PhotoRenderer(() -> { reads.incrementAndGet(); return null; });
        MapCanvas canvas = mock(MapCanvas.class);
        renderer.render(mock(MapView.class), canvas, null);
        renderer.render(mock(MapView.class), canvas, null);
        assertEquals(1, reads.get());
        verify(canvas, never()).setPixel(anyInt(), anyInt(), anyByte());
    }

    @Test
    void storeReadsThePngWhenAskedNotWhenTheSupplierIsMade() throws Exception {
        PhotoStore store = new PhotoStore(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        List<Exception> failures = new ArrayList<>();
        var later = store.later(7, rgb -> rgb == 0x112233 ? 3 : 0, failures::add);
        byte[] pixels = new byte[Optics.SIZE * Optics.SIZE];
        pixels[9] = 1;
        store.save(7, pixels, index -> index == 1 ? 0x112233 : 0xFFFFFF);
        byte[] read = later.get();
        assertNotNull(read, "the file written after the supplier was made must be read");
        assertEquals(3, read[9]);
        assertEquals(0, read[0]);
        assertNull(store.later(8, rgb -> 0, failures::add).get());
        assertEquals(List.of(), failures);
    }
}
