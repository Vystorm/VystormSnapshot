package at.esoren.vystormsnapshot;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntUnaryOperator;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/**
 * Photos are kept as plain 128×128 PNG files ({@code photos/<map id>.png}), so admins can look at, back up or publish
 * them. The map colour indices are rebuilt from the PNG on load.
 */
final class PhotoStore {
    private final Path folder;
    private final Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files;

    PhotoStore(Path folder, Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface files) { this.folder = folder; this.files = files; }

    Path file(int mapId) { return folder.resolve(mapId + ".png"); }

    /** Writes the photo; {@code toRgb} turns a map colour index into 0xRRGGBB. */
    void save(int mapId, byte[] pixels, IntUnaryOperator toRgb) throws IOException {
        Files.createDirectories(folder);
        BufferedImage image = new BufferedImage(Optics.SIZE, Optics.SIZE, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < pixels.length; i++) image.setRGB(i % Optics.SIZE, i / Optics.SIZE, toRgb.applyAsInt(pixels[i]));
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", png)) throw new IOException("No PNG writer available");
        files.write(file(mapId), png.toByteArray());
    }

    /** RGB values of a stored photo (row by row), or null if there is none or it is not 128×128. */
    int[] load(int mapId) throws IOException {
        Path file = file(mapId);
        if (!Files.exists(file)) return null;
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null || image.getWidth() != Optics.SIZE || image.getHeight() != Optics.SIZE) return null;
        int[] rgb = new int[Optics.SIZE * Optics.SIZE];
        for (int i = 0; i < rgb.length; i++) rgb[i] = image.getRGB(i % Optics.SIZE, i / Optics.SIZE) & 0xFFFFFF;
        return rgb;
    }

    /**
     * Map colour indices of a stored photo, read from the PNG only when the supplier is asked (a player sees the map);
     * null if the file is missing, not 128×128 or unreadable ({@code onFailure} hears about read errors).
     */
    Supplier<byte[]> later(int mapId, IntUnaryOperator match, Consumer<IOException> onFailure) {
        return () -> {
            try {
                int[] rgb = load(mapId);
                if (rgb == null) return null;
                byte[] pixels = new byte[rgb.length];
                for (int i = 0; i < rgb.length; i++) pixels[i] = (byte) match.applyAsInt(rgb[i]);
                return pixels;
            } catch (IOException failure) {
                onFailure.accept(failure);
                return null;
            }
        };
    }

    List<Integer> ids() throws IOException {
        List<Integer> out = new ArrayList<>();
        if (!Files.isDirectory(folder)) return out;
        try (var files = Files.list(folder)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                String name = file.getFileName().toString();
                if (!name.endsWith(".png")) continue;
                try { out.add(Integer.parseInt(name.substring(0, name.length() - 4))); } catch (NumberFormatException ignored) { }
            }
        }
        return out;
    }
}
