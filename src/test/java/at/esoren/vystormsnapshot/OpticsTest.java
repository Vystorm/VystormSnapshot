package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpticsTest {
    @TempDir Path folder;

    private static void near(double[] expected, double[] actual) {
        for (int i = 0; i < 3; i++) assertEquals(expected[i], actual[i], 0.02, "component " + i);
    }

    @Test
    void forwardMatchesMinecraftAxes() {
        near(new double[]{0, 0, 1}, Optics.forward(0, 0));
        near(new double[]{-1, 0, 0}, Optics.forward(90, 0));
        near(new double[]{0, -1, 0}, Optics.forward(0, 90));
    }

    @Test
    void centreRayLooksForwardAndCornersSpread() {
        double[] centre = Optics.ray(0, 0, 70, 64, 64);
        assertTrue(centre[2] > 0.99);
        double[] topLeft = Optics.ray(0, 0, 70, 0, 0);
        assertTrue(topLeft[1] > 0.3, "top row looks up");
        assertTrue(topLeft[0] > 0.3, "left column looks east when facing south");
        double[] bottomRight = Optics.ray(0, 0, 70, 127, 127);
        assertTrue(bottomRight[1] < -0.3 && bottomRight[0] < -0.3);
    }

    @Test
    void raysStayUnitLengthEvenStraightDown() {
        double[] down = Optics.ray(45, 90, 70, 10, 100);
        assertEquals(1, Math.sqrt(down[0] * down[0] + down[1] * down[1] + down[2] * down[2]), 1e-9);
        assertTrue(down[1] < -0.7);
    }

    @Test
    void daylightCycle() {
        assertEquals(1.0, Optics.daylight(6000));
        assertEquals(0.15, Optics.daylight(18000));
        assertTrue(Optics.daylight(13000) < 1 && Optics.daylight(13000) > 0.15);
    }

    @Test
    void shadingDarkensSidesAndFogsDistance() {
        assertEquals(0xFFFFFF, Optics.shade(0xFFFFFF, Optics.Face.UP, 1.0, 0, 100, 0x000000));
        assertEquals(0x808080, Optics.shade(0xFFFFFF, Optics.Face.DOWN, 1.0, 0, 100, 0x000000) + 0x010101 * 0);
        int far = Optics.shade(0xFF0000, Optics.Face.UP, 1.0, 100, 100, 0x0000FF);
        assertTrue((far & 0xFF) > 150, "far pixels lean towards the fog colour");
    }

    @Test
    void lightNeverBlack() {
        assertEquals(0.22, Optics.light(0, 0, 1.0), 1e-9);
        assertEquals(1.0, Optics.light(15, 0, 1.0), 1e-9);
        assertEquals(1.0, Optics.light(0, 15, 0.15), 1e-9);
    }

    @Test
    void entityColoursFallBackByName() {
        assertEquals(0x4E7A36, PhotoJob.entityColor("ZOMBIE"));
        assertEquals(0x4E7A36, PhotoJob.entityColor("ZOMBIE_VILLAGER"));
        assertEquals(0x8A7A6A, PhotoJob.entityColor("SNIFFER"));
    }

    @Test
    void photoStoreRoundTripsColours() throws Exception {
        PhotoStore store = new PhotoStore(folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        byte[] pixels = new byte[Optics.SIZE * Optics.SIZE];
        pixels[5] = 1;
        store.save(42, pixels, index -> index == 1 ? 0x112233 : 0xFFFFFF);
        int[] rgb = store.load(42);
        assertEquals(0x112233, rgb[5]);
        assertEquals(0xFFFFFF, rgb[0]);
        assertEquals(java.util.List.of(42), store.ids());
    }
}
