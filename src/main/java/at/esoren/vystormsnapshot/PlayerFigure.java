package at.esoren.vystormsnapshot;

import java.util.*;
import org.bukkit.block.BlockFace;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

/** Six skin-mapped cuboids captured at shutter time; no live player reads during rendering. */
final class PlayerFigure {
    record Skin(SurfaceTexture image, boolean slim) {}
    record Hit(double distance, int color, BlockFace face) {}
    private record Part(BoundingBox box, int u, int v, int overlayU, int overlayV, int width, int height, int depth) {}
    private final Skin skin;
    private final Vector feet;
    private final double cos, sin, unit;
    private final List<Part> parts = new ArrayList<>();
    PlayerFigure(Skin skin, Vector feet, double yaw, double height) {
        this.skin = skin;
        this.feet = feet.clone();
        cos = Math.cos(Math.toRadians(yaw));
        sin = Math.sin(Math.toRadians(yaw));
        unit = Math.clamp(height, 0.5, 4) / 32;
        boolean old = skin.image.height == 32;
        part(-4, 24, -4, 8, 8, 8, 0, 0, 32, 0);
        part(-4, 12, -2, 8, 12, 4, 16, 16, 16, 32);
        int arm = skin.slim ? 3 : 4;
        part(-4 - arm, 12, -2, arm, 12, 4, 40, 16, 40, 32);
        part(4, 12, -2, arm, 12, 4, old ? 40 : 32, old ? 16 : 48, 48, 48);
        part(-4, 0, -2, 4, 12, 4, 0, 16, 0, 32);
        part(0, 0, -2, 4, 12, 4, old ? 0 : 16, old ? 16 : 48, 0, 48);
    }
    private void part(double x, double y, double z, int w, int h, int d, int u, int v, int ou, int ov) {
        parts.add(new Part(new BoundingBox(x * unit, y * unit, z * unit, (x + w) * unit, (y + h) * unit, (z + d) * unit), u, v, ou, ov, w, h, d));
    }
    Hit hit(Vector origin, Vector direction, double max) {
        Vector o = local(origin.clone().subtract(feet)), d = local(direction);
        Hit best = null;
        for (Part part : parts) {
            var hit = part.box.rayTrace(o, d, best == null ? max : best.distance);
            if (hit == null) continue;
            double distance = hit.getHitPosition().distance(o);
            if (best != null && distance >= best.distance) continue;
            Vector p = hit.getHitPosition();
            BoundingBox b = part.box;
            BlockFace face = hit.getHitBlockFace();
            if (face == null) continue;
            double x = (p.getX() - b.getMinX()) / b.getWidthX(), y = (p.getY() - b.getMinY()) / b.getHeight(), z = (p.getZ() - b.getMinZ()) / b.getWidthZ();
            int rgb = sample(part, face, x, y, z, false) & 0xffffff;
            if (skin.image.height == 64 || part.v == 0) rgb = SurfaceTexture.composite(sample(part, face, x, y, z, true), rgb, 0xffffff, false);
            best = new Hit(distance, rgb, face);
        }
        return best;
    }
    private Vector local(Vector v) {
        return new Vector(cos * v.getX() + sin * v.getZ(), v.getY(), -sin * v.getX() + cos * v.getZ());
    }
    private int sample(Part p, BlockFace face, double x, double y, double z, boolean overlay) {
        int u = overlay ? p.overlayU : p.u, v = overlay ? p.overlayV : p.v;
        double a, b;
        int w, h;
        switch (face) {
            case UP -> {
                u += p.depth;
                a = x;
                b = 1 - z;
                w = p.width;
                h = p.depth;
            }
            case DOWN -> {
                u += p.depth + p.width;
                a = x;
                b = z;
                w = p.width;
                h = p.depth;
            }
            case SOUTH -> {
                u += p.depth;
                v += p.depth;
                a = x;
                b = 1 - y;
                w = p.width;
                h = p.height;
            }
            case NORTH -> {
                u += 2 * p.depth + p.width;
                v += p.depth;
                a = 1 - x;
                b = 1 - y;
                w = p.width;
                h = p.height;
            }
            case WEST -> {
                v += p.depth;
                a = z;
                b = 1 - y;
                w = p.depth;
                h = p.height;
            }
            default -> {
                u += p.depth + p.width;
                v += p.depth;
                a = 1 - z;
                b = 1 - y;
                w = p.depth;
                h = p.height;
            }
        }
        return skin.image.pixel(u + (int) (Math.clamp(a, 0, .999999) * w), v + (int) (Math.clamp(b, 0, .999999) * h));
    }
}
