package at.esoren.vystormsnapshot;

import java.util.Locale;
import org.bukkit.Material;

/**
 * Film styles and photo frames chosen in the camera menu. A style turns each rendered colour into another one before it
 * is matched to the map palette; a frame paints a border around the whole picture (across all maps of a big photo).
 */
enum Style {
    COLOR("color", Material.RED_DYE),
    BLACK_WHITE("black-white", Material.GRAY_DYE),
    SEPIA("sepia", Material.BROWN_DYE),
    VINTAGE("vintage", Material.ORANGE_DYE),
    NOIR("noir", Material.BLACK_DYE),
    VIVID("vivid", Material.LIME_DYE);

    final String id;
    final Material icon;

    Style(String id, Material icon) {
        this.id = id;
        this.icon = icon;
    }

    /** "black-white" → BLACK_WHITE; null for unknown names. */
    static Style parse(String text) {
        if (text == null) return null;
        String wanted = text.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        for (Style style : values()) if (style.id.equals(wanted)) return style;
        return null;
    }

    /** The colour of pixel ({@code x}, {@code y}) of a {@code width}×{@code height} picture in this style. */
    int apply(int rgb, int x, int y, int width, int height) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        double grey = 0.299 * r + 0.587 * g + 0.114 * b;
        return switch (this) {
            case COLOR -> rgb & 0xFFFFFF;
            case BLACK_WHITE -> rgb(grey, grey, grey);
            case SEPIA -> rgb(grey * 1.07 + 20, grey * 0.87 + 12, grey * 0.62);
            case NOIR -> {
                // hard contrast around the middle grey and a dark edge
                double v = (grey - 128) * 1.7 + 118;
                v *= vignette(x, y, width, height, 0.45);
                yield rgb(v, v, v);
            }
            case VINTAGE -> {
                // warm, faded blacks, slightly washed out, darker corners
                double fade = vignette(x, y, width, height, 0.3);
                yield rgb((r * 0.85 + grey * 0.15 + 28) * fade, (g * 0.8 + grey * 0.2 + 18) * fade, (b * 0.65 + grey * 0.35 + 8) * fade);
            }
            case VIVID -> rgb(grey + (r - grey) * 1.6, grey + (g - grey) * 1.6, grey + (b - grey) * 1.6);
        };
    }

    /** 1 in the middle, down to {@code 1 - strength} in the corners. */
    static double vignette(int x, int y, int width, int height, double strength) {
        double dx = (x + 0.5) / width * 2 - 1, dy = (y + 0.5) / height * 2 - 1;
        double d = Math.min(1, (dx * dx + dy * dy) / 2);
        return 1 - strength * d * d;
    }

    static int rgb(double r, double g, double b) {
        return (clamp(r) << 16) | (clamp(g) << 8) | clamp(b);
    }

    private static int clamp(double v) { return (int) Math.round(Math.clamp(v, 0, 255)); }

    /** Borders around the picture: none, a white instant-photo frame (wider at the bottom) or a thin black one. */
    enum Frame {
        NONE("none", Material.GLASS_PANE, 0),
        WHITE("white", Material.WHITE_STAINED_GLASS_PANE, 0xF4F1E8),
        BLACK("black", Material.BLACK_STAINED_GLASS_PANE, 0x161616);

        final String id;
        final Material icon;
        final int color;

        Frame(String id, Material icon, int color) {
            this.id = id;
            this.icon = icon;
            this.color = color;
        }

        static Frame parse(String text) {
            if (text == null) return null;
            String wanted = text.trim().toLowerCase(Locale.ROOT);
            for (Frame frame : values()) if (frame.id.equals(wanted)) return frame;
            return null;
        }

        /**
         * True when pixel ({@code x}, {@code y}) lies on the frame. The border is 1/20 of the shorter side (at least 3
         * pixels), the white frame is three times as wide at the bottom like an instant photo.
         */
        boolean covers(int x, int y, int width, int height) {
            if (this == NONE) return false;
            int border = Math.max(3, Math.min(width, height) / 20);
            int bottom = this == WHITE ? border * 3 : border;
            return x < border || y < border || x >= width - border || y >= height - bottom;
        }
    }
}
