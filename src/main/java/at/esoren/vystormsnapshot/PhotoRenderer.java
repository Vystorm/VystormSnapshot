package at.esoren.vystormsnapshot;

import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/** Draws a finished photo onto its map once; the canvas keeps it from then on. */
final class PhotoRenderer extends MapRenderer {
    private final byte[] pixels;
    private boolean drawn;

    PhotoRenderer(byte[] pixels) {
        super(false);
        this.pixels = pixels;
    }

    @Override
    @SuppressWarnings({"deprecation", "removal"})
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (drawn) return;
        for (int i = 0; i < pixels.length; i++) canvas.setPixel(i % Optics.SIZE, i / Optics.SIZE, pixels[i]);
        drawn = true;
    }
}
