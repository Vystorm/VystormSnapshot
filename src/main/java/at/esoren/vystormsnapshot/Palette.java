package at.esoren.vystormsnapshot;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.map.MapPalette;

/** RGB → map colour index, cached (matching against the palette is the slow part of rendering). */
final class Palette {
    private final Map<Integer, Byte> cache = new HashMap<>();

    @SuppressWarnings({"deprecation", "removal"})
    byte match(int rgb) {
        rgb = (rgb & 0xF8F8F8) | 0x040404;
        Byte known = cache.get(rgb);
        if (known != null) return known;
        byte index = MapPalette.matchColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        if (cache.size() < 65_536) cache.put(rgb, index);
        return index;
    }

    @SuppressWarnings({"deprecation", "removal"})
    static java.awt.Color color(byte index) { return MapPalette.getColor(index); }
}
