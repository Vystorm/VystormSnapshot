package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FormatTest {
    @Test
    void parsesFormats() {
        assertArrayEquals(new int[]{2, 1}, Format.parse("2x1"));
        assertArrayEquals(new int[]{3, 2}, Format.parse(" 3X2 "));
        assertNull(Format.parse("5x1"));
        assertNull(Format.parse("2"));
        assertNull(Format.parse(null));
    }

    @Test
    void slicesTiles() {
        int width = 2 * Optics.SIZE;
        byte[] pixels = new byte[width * 2 * Optics.SIZE];
        pixels[(Optics.SIZE + 3) * width + Optics.SIZE + 5] = 7; // tile (1,1), pixel (5,3)
        assertEquals(7, Format.tile(pixels, width, 1, 1)[3 * Optics.SIZE + 5]);
        assertEquals(0, Format.tile(pixels, width, 0, 1)[3 * Optics.SIZE + 5]);
    }

    @Test
    void compass() {
        assertEquals("S", Format.facing(0));
        assertEquals("W", Format.facing(90));
        assertEquals("N", Format.facing(180));
        assertEquals("E", Format.facing(-90));
    }

    @Test
    void wideRaysKeepAspect() {
        double[] corner = Optics.ray(0, 0, 70, 0, 0, 256, 128);
        double[] square = Optics.ray(0, 0, 70, 0, 0, 128, 128);
        assertTrue(corner[1] < square[1], "a 2x1 picture sees less upwards at its corner");
    }

    @Test
    void flashFadesWithDistance() {
        assertEquals(0.95, Optics.flash(0.2, 0, 16), 1e-9);
        assertEquals(0.2, Optics.flash(0.2, 20, 16), 1e-9);
        assertEquals(1.0, Optics.flash(1.0, 2, 16), 1e-9);
    }
}
