package at.esoren.vystormsnapshot;

import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Draws a finished photo onto its map once; the canvas keeps it from then on. A stored photo is read from its PNG only
 * when a player first sees the map, and the pixels are let go after drawing, so photos nobody looks at during an uptime
 * cost no memory and no start-up time.
 */
final class PhotoRenderer extends MapRenderer {
    private Supplier<byte[]> source;
    private boolean drawn;

    PhotoRenderer(byte[] pixels) {
        this(() -> pixels);
    }

    /** {@code source} is asked once, on the first render; null means the photo cannot be read and the map stays empty. */
    PhotoRenderer(Supplier<byte[]> source) {
        super(false);
        this.source = source;
    }

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (drawn) return;
        drawn = true;
        Supplier<byte[]> from = source;
        source = null;
        byte[] pixels = from == null ? null : from.get();
        if (pixels == null) return;
        for (int i = 0; i < pixels.length; i++) canvas.setPixel(i % Optics.SIZE, i / Optics.SIZE, pixels[i]);
    }
}
