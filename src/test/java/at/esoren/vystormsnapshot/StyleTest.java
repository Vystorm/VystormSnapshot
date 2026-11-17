package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class StyleTest {
    private static int r(int rgb) { return (rgb >> 16) & 0xFF; }
    private static int g(int rgb) { return (rgb >> 8) & 0xFF; }
    private static int b(int rgb) { return rgb & 0xFF; }

    @Test
    void parsesNamesFromConfigAndItems() {
        assertEquals(Style.BLACK_WHITE, Style.parse("black-white"));
        assertEquals(Style.BLACK_WHITE, Style.parse(" BLACK_WHITE "));
        assertEquals(Style.SEPIA, Style.parse("Sepia"));
        assertNull(Style.parse("rainbow"));
        assertNull(Style.parse(null));
        assertEquals(Style.Frame.WHITE, Style.Frame.parse("white"));
        assertNull(Style.Frame.parse("gold"));
    }

    @Test
    void colorKeepsThePixel() {
        assertEquals(0x3A63A8, Style.COLOR.apply(0x3A63A8, 0, 0, 128, 128));
        assertEquals(0x3A63A8, Style.COLOR.apply(0xFF3A63A8, 5, 5, 128, 128), "alpha bits are dropped");
    }

    @Test
    void blackWhiteIsGrey() {
        int grey = Style.BLACK_WHITE.apply(0x3A63A8, 64, 64, 128, 128);
        assertEquals(r(grey), g(grey));
        assertEquals(g(grey), b(grey));
        assertEquals(0xFFFFFF, Style.BLACK_WHITE.apply(0xFFFFFF, 0, 0, 128, 128));
    }

    @Test
    void sepiaIsWarm() {
        int sepia = Style.SEPIA.apply(0x808080, 64, 64, 128, 128);
        assertTrue(r(sepia) > g(sepia) && g(sepia) > b(sepia));
    }

    @Test
    void noirHasMoreContrastAndDarkCorners() {
        int dark = Style.NOIR.apply(0x505050, 64, 64, 128, 128), light = Style.NOIR.apply(0xB0B0B0, 64, 64, 128, 128);
        assertTrue(r(dark) < 0x50 && r(light) > 0xB0);
        assertTrue(r(Style.NOIR.apply(0xB0B0B0, 0, 0, 128, 128)) < r(light), "corner darker than centre");
    }

    @Test
    void vividSaturatesAndVintageFades() {
        int vivid = Style.VIVID.apply(0x806040, 64, 64, 128, 128);
        assertTrue(r(vivid) - b(vivid) > 0x80 - 0x40);
        assertTrue(r(Style.VINTAGE.apply(0x000000, 64, 64, 128, 128)) > 0, "blacks fade");
    }

    @Test
    void vignetteIsOneInTheMiddle() {
        assertEquals(1, Style.vignette(64, 64, 128, 128, 0.5), 0.001);
        assertTrue(Style.vignette(0, 0, 128, 128, 0.5) < 0.6);
    }

    @Test
    void framesCoverTheBorderOfTheWholePicture() {
        assertFalse(Style.Frame.NONE.covers(0, 0, 128, 128));
        // 128 -> border 6, white bottom 18
        assertTrue(Style.Frame.WHITE.covers(0, 64, 128, 128));
        assertTrue(Style.Frame.WHITE.covers(64, 5, 128, 128));
        assertFalse(Style.Frame.WHITE.covers(64, 6, 128, 128));
        assertTrue(Style.Frame.WHITE.covers(64, 110, 128, 128));
        assertFalse(Style.Frame.BLACK.covers(64, 110, 128, 128));
        assertTrue(Style.Frame.BLACK.covers(64, 122, 128, 128));
        // a 2x1 photo: the seam between both maps is not framed
        assertFalse(Style.Frame.BLACK.covers(127, 64, 256, 128));
        assertFalse(Style.Frame.BLACK.covers(128, 64, 256, 128));
    }

    @Test
    void cameraMenuLayout() {
        var slots = CameraMenu.layout(List.of(Style.COLOR, Style.SEPIA), List.of("1x1", "2x1"), List.of(Style.Frame.values()), true);
        assertEquals("style:color", slots.get(0));
        assertEquals("style:sepia", slots.get(1));
        assertEquals("format:2x1", slots.get(10));
        assertEquals("frame:white", slots.get(28));
        assertEquals("album", slots.get(CameraMenu.ALBUM_SLOT));
        assertEquals("gallery", slots.get(CameraMenu.GALLERY_SLOT));
        var small = CameraMenu.layout(List.of(Style.COLOR), List.of("1x1"), List.of(Style.Frame.NONE), false);
        assertNull(small.get(9), "a single format is not offered");
        assertNull(small.get(27), "a single frame is not offered");
        assertNull(small.get(CameraMenu.GALLERY_SLOT));
        assertNull(small.get(30), "no timer slots by default");
    }

    @Test
    void selfTimerChoicesInTheCameraMenu() {
        var slots = CameraMenu.layout(List.of(Style.COLOR), List.of("1x1"), List.of(Style.Frame.NONE), Format.timers(List.of(10, 0, 3, 3, 99, -1)), false);
        assertEquals("timer:0", slots.get(30));
        assertEquals("timer:3", slots.get(31));
        assertEquals("timer:10", slots.get(32));
        assertEquals(List.of(0), Format.timers(List.of(45)));
        assertNull(CameraMenu.layout(List.of(Style.COLOR), List.of("1x1"), List.of(Style.Frame.NONE), List.of(0), false).get(30));
    }
}
