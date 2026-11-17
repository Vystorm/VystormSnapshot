package at.esoren.vystormsnapshot;

/**
 * Pure camera maths and colour rules (no server needed, unit-tested): ray directions for each map pixel, face shading,
 * light, fog and sky colours. Colours are packed {@code 0xRRGGBB} ints.
 */
public final class Optics {
    public static final int SIZE = 128;

    /** Block face a ray hit, reduced to what shading needs. */
    public enum Face { UP, DOWN, NORTH_SOUTH, EAST_WEST }

    private Optics() {}

    /** Minecraft look direction for yaw/pitch in degrees (yaw 0 = south/+Z, pitch 90 = straight down). */
    public static double[] forward(double yaw, double pitch) {
        double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
        return new double[]{-Math.sin(y) * Math.cos(p), -Math.sin(p), Math.cos(y) * Math.cos(p)};
    }

    /**
     * Unit ray through pixel ({@code px}, {@code py}) of a {@code SIZE}×{@code SIZE} image, top-left first, for a
     * camera with the given horizontal field of view. Works at any pitch, including straight up or down.
     */
    public static double[] ray(double yaw, double pitch, double fovDegrees, int px, int py) {
        return ray(yaw, pitch, fovDegrees, px, py, SIZE, SIZE);
    }

    /** Ray for an image of {@code width}×{@code height} pixels; the field of view is horizontal, height follows the aspect. */
    public static double[] ray(double yaw, double pitch, double fovDegrees, int px, int py, int width, int height) {
        double[] f = forward(yaw, pitch);
        double y = Math.toRadians(yaw);
        double[] right = {-Math.cos(y), 0, -Math.sin(y)};
        double[] up = {right[1] * f[2] - right[2] * f[1], right[2] * f[0] - right[0] * f[2], right[0] * f[1] - right[1] * f[0]};
        double tan = Math.tan(Math.toRadians(fovDegrees) / 2);
        double u = ((px + 0.5) / width * 2 - 1) * tan;
        double v = (1 - (py + 0.5) / height * 2) * tan * height / width;
        double[] d = {f[0] + right[0] * u + up[0] * v, f[1] + right[1] * u + up[1] * v, f[2] + right[2] * u + up[2] * v};
        double length = Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]);
        return new double[]{d[0] / length, d[1] / length, d[2] / length};
    }

    /** Fraction of daylight for a world time (0 = sunrise, 6000 = noon, 18000 = midnight): 1 by day, 0.15 at night. */
    public static double daylight(long time) {
        long t = Math.floorMod(time, 24000L);
        if (t < 12000) return 1.0;
        if (t < 13800) return 1.0 - (t - 12000) / 1800.0 * 0.85;
        if (t < 22200) return 0.15;
        return 0.15 + (t - 22200) / 1800.0 * 0.85;
    }

    /** Sky colour for a ray with vertical component {@code dy} (-1..1) and the given daylight. */
    public static int sky(double dy, double daylight) {
        int day = blend(0xA9CCF5, 0x5A8FD8, Math.clamp(dy, 0, 1));
        int night = blend(0x1A2140, 0x070A18, Math.clamp(dy, 0, 1));
        int color = blend(night, day, Math.clamp((daylight - 0.15) / 0.85, 0, 1));
        return dy < 0 ? blend(color, 0x2B2B2B, Math.clamp(-dy * 2, 0, 0.6)) : color;
    }

    /** Brightness factor of a face: top full, bottom half, sides in between (classic Minecraft look). */
    public static double face(Face face) {
        return switch (face) {
            case UP -> 1.0;
            case DOWN -> 0.5;
            case NORTH_SOUTH -> 0.8;
            case EAST_WEST -> 0.65;
        };
    }

    /** Camera flash: extra light that fades over {@code range} blocks. */
    public static double flash(double light, double distance, double range) {
        if (range <= 0 || distance >= range) return light;
        return Math.max(light, 0.95 - 0.6 * distance / range);
    }

    /** Light factor from sky light (scaled by daylight) and block light, never fully black. */
    public static double light(int skyLight, int blockLight, double daylight) {
        double level = Math.max(skyLight * daylight, blockLight) / 15.0;
        return 0.22 + 0.78 * Math.clamp(level, 0, 1);
    }

    /** Final pixel: base colour × face × light, then fogged towards {@code fog} with distance. */
    public static int shade(int base, Face face, double light, double distance, double maxDistance, int fog) {
        int lit = scale(base, face(face) * light);
        double t = Math.clamp(distance / maxDistance, 0, 1);
        return blend(lit, fog, t * t * 0.7);
    }

    public static int scale(int rgb, double factor) {
        int r = (int) Math.clamp(Math.round(((rgb >> 16) & 0xFF) * factor), 0, 255);
        int g = (int) Math.clamp(Math.round(((rgb >> 8) & 0xFF) * factor), 0, 255);
        int b = (int) Math.clamp(Math.round((rgb & 0xFF) * factor), 0, 255);
        return (r << 16) | (g << 8) | b;
    }

    /** Linear blend from {@code a} (t = 0) to {@code b} (t = 1). */
    public static int blend(int a, int b, double t) {
        t = Math.clamp(t, 0, 1);
        int r = (int) Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int g = (int) Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = (int) Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return (r << 16) | (g << 8) | bl;
    }
}
