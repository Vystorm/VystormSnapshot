package at.esoren.vystormsnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Vehicle;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

/**
 * One photo being taken: casts one ray per map pixel from the camera position, a few rows per tick so even large
 * servers never stall. Entities are captured once at the shutter moment (their boxes), blocks are read row by row.
 */
final class PhotoJob {
    private record Target(BoundingBox box, int color, PlayerFigure figure) {}

    private static final Map<String, Integer> ENTITY_COLORS = Map.ofEntries(
            Map.entry("ZOMBIE", 0x4E7A36), Map.entry("HUSK", 0x9C8A5A), Map.entry("DROWNED", 0x3E8A7E), Map.entry("SKELETON", 0xC9C9C9),
            Map.entry("STRAY", 0xA9BDC0), Map.entry("CREEPER", 0x55B04A), Map.entry("SPIDER", 0x3A3030), Map.entry("ENDERMAN", 0x161616),
            Map.entry("PIG", 0xF0A5A2), Map.entry("COW", 0x4A3324), Map.entry("MOOSHROOM", 0xA12A22), Map.entry("SHEEP", 0xE6E6E6),
            Map.entry("CHICKEN", 0xF2F2F2), Map.entry("HORSE", 0x8B5A2B), Map.entry("DONKEY", 0x7A6A5A), Map.entry("MULE", 0x5A3A22),
            Map.entry("CAMEL", 0xC9A060), Map.entry("LLAMA", 0xD8C8A8), Map.entry("WOLF", 0xD8D0C8), Map.entry("CAT", 0xC08040),
            Map.entry("FOX", 0xD8742A), Map.entry("VILLAGER", 0x9C6B4A), Map.entry("WANDERING_TRADER", 0x3A5A9A), Map.entry("IRON_GOLEM", 0xC8C0B8),
            Map.entry("SLIME", 0x70C060), Map.entry("BEE", 0xE8C030), Map.entry("AXOLOTL", 0xF0A0C0), Map.entry("PLAYER", 0x3A63A8),
            Map.entry("ARMOR_STAND", 0x9A7A50), Map.entry("BOAT", 0x9A7A50), Map.entry("MINECART", 0x7A7A7A), Map.entry("GHAST", 0xF0F0F0),
            Map.entry("BLAZE", 0xE8A020), Map.entry("WITCH", 0x4A2A5A), Map.entry("PILLAGER", 0x5A5A5A), Map.entry("PANDA", 0xEEEEEE));

    private final World world;
    private final Vector origin;
    private final double yaw, pitch, fov, maxDistance;
    private final double daylight;
    private final boolean nether, end;
    private final List<Target> targets = new ArrayList<>();
    private final int width, height;
    private final double flashRange;
    private final byte[] pixels;
    private final Consumer<byte[]> done;
    private final Style style;
    private final Style.Frame frame;
    private final BlockTextures textures;
    private int row;

    PhotoJob(Location eye, Entity photographer, double fov, double maxDistance, Consumer<byte[]> done) {
        this(eye, photographer, fov, maxDistance, 1, 1, 0, done);
    }

    /** {@code tilesX}×{@code tilesY} maps in one picture; {@code flashRange} > 0 lights up the near scene. */
    PhotoJob(Location eye, Entity photographer, double fov, double maxDistance, int tilesX, int tilesY, double flashRange, Consumer<byte[]> done) {
        this(eye, photographer, fov, maxDistance, tilesX, tilesY, flashRange, Style.COLOR, Style.Frame.NONE, done);
    }

    /** As above, developed in a film {@code style} and with a {@code frame} around the whole picture. */
    PhotoJob(Location eye, Entity photographer, double fov, double maxDistance, int tilesX, int tilesY, double flashRange,
             Style style, Style.Frame frame, Consumer<byte[]> done) {
        this(eye,photographer,fov,maxDistance,tilesX,tilesY,flashRange,style,frame,BlockTextures.EMPTY,null,done);
    }

    PhotoJob(Location eye, Entity photographer, double fov, double maxDistance, int tilesX, int tilesY, double flashRange,
             Style style, Style.Frame frame, BlockTextures textures, SkinCache skins, Consumer<byte[]> done) {
        this(eye,photographer,photographer instanceof Player p?p:null,fov,maxDistance,tilesX,tilesY,flashRange,style,frame,textures,skins,done);
    }

    PhotoJob(Location eye, Entity photographer, Player viewer, double fov, double maxDistance, int tilesX, int tilesY, double flashRange,
             Style style, Style.Frame frame, BlockTextures textures, SkinCache skins, Consumer<byte[]> done) {
        this.textures=textures;
        this.style = style;
        this.frame = frame;
        this.width = tilesX * Optics.SIZE;
        this.height = tilesY * Optics.SIZE;
        this.flashRange = flashRange;
        this.pixels = new byte[width * height];
        this.world = eye.getWorld();
        this.origin = eye.toVector();
        this.yaw = eye.getYaw();
        this.pitch = eye.getPitch();
        this.fov = fov;
        this.maxDistance = maxDistance;
        this.daylight = Optics.daylight(world.getTime());
        this.nether = world.getEnvironment() == World.Environment.NETHER;
        this.end = world.getEnvironment() == World.Environment.THE_END;
        this.done = done;
        for (Entity entity : world.getNearbyEntities(eye, maxDistance, maxDistance, maxDistance)) {
            if (entity.equals(photographer) || !(entity instanceof LivingEntity || entity instanceof Vehicle)) continue;
            if (entity instanceof LivingEntity living && living.isInvisible()) continue;
            if(viewer!=null && !entity.equals(viewer) && !viewer.canSee(entity))continue;
            PlayerFigure figure=null;
            if(skins!=null && entity instanceof Player player) {
                skins.request(player);
                PlayerFigure.Skin skin=skins.get(player.getUniqueId());
                if(skin!=null)figure=new PlayerFigure(skin,player.getLocation().toVector(),player.getLocation().getYaw(),player.getBoundingBox().getHeight());
            }
            targets.add(new Target(entity.getBoundingBox(), entityColor(entity.getType().name()),figure));
            if (targets.size() >= 64) break;
        }
    }

    static int entityColor(String type) {
        Integer exact = ENTITY_COLORS.get(type.toUpperCase(Locale.ROOT));
        if (exact != null) return exact;
        // ZOMBIE_VILLAGER → ZOMBIE: the earliest match in the name wins (the creature, not what it was)
        String upper = type.toUpperCase(Locale.ROOT);
        int bestAt = Integer.MAX_VALUE, color = 0x8A7A6A;
        for (Map.Entry<String, Integer> entry : ENTITY_COLORS.entrySet()) {
            int at = upper.indexOf(entry.getKey());
            if (at >= 0 && at < bestAt) { bestAt = at; color = entry.getValue(); }
        }
        return color;
    }

    private int background(double dy) {
        if (nether) return 0x3A0E0E;
        if (end) return 0x14101E;
        return Optics.sky(dy, daylight);
    }

    private static Optics.Face face(BlockFace face) {
        if (face == null) return Optics.Face.UP;
        return switch (face) {
            case UP -> Optics.Face.UP;
            case DOWN -> Optics.Face.DOWN;
            case NORTH, SOUTH -> Optics.Face.NORTH_SOUTH;
            default -> Optics.Face.EAST_WEST;
        };
    }

    /** Renders up to {@code rows} rows; returns true when the photo is complete (and has been handed over). */
    boolean step(int rows, Palette palette) {
        int end = Math.min(height, row + rows);
        for (; row < end; row++) {
            for (int x = 0; x < width; x++) {
                // frame pixels need no ray at all
                int rgb = frame.covers(x, row, width, height) ? frame.color : style.apply(pixel(x, row), x, row, width, height);
                pixels[row * width + x] = palette.match(rgb);
            }
        }
        if (row < height) return false;
        done.accept(pixels);
        return true;
    }

    private int pixel(int x, int y) {
        double[] d = Optics.ray(yaw, pitch, fov, x, y, width, height);
        Vector direction = new Vector(d[0], d[1], d[2]);
        int fog = background(d[1]);
        double best = maxDistance;
        int color = -1;
        // Blocks without a map colour (glass, barriers, light blocks) are looked through: continue behind them.
        Vector start = origin.clone();
        double travelled = 0;
        for (int pass = 0; pass < 6 && travelled < maxDistance; pass++) {
            RayTraceResult hit = world.rayTraceBlocks(start.toLocation(world), direction, maxDistance - travelled, FluidCollisionMode.SOURCE_ONLY, false);
            if (hit == null || hit.getHitBlock() == null) break;
            Block block = hit.getHitBlock();
            double distance = hit.getHitPosition().distance(origin);
            Color base = block.getBlockData().getMapColor();
            int rgb = base == null ? 0 : base.asRGB();
            if (rgb == 0) {
                travelled = distance + 0.05;
                start = hit.getHitPosition().add(direction.clone().multiply(0.05));
                // step out of the transparent block so the next trace does not hit it again
                while (start.getBlockX() == block.getX() && start.getBlockY() == block.getY() && start.getBlockZ() == block.getZ() && travelled < maxDistance) {
                    start.add(direction.clone().multiply(0.1));
                    travelled += 0.1;
                }
                continue;
            }
            Block air = hit.getHitBlockFace() == null ? block : block.getRelative(hit.getHitBlockFace());
            rgb=textures.sample(block.getBlockData(),hit.getHitBlockFace(),hit.getHitPosition().clone().subtract(new Vector(block.getX(),block.getY(),block.getZ())),rgb);
            double light = Optics.light(air.getLightFromSky(), air.getLightFromBlocks(), nether || end ? 0.6 : daylight);
            light = Optics.flash(light, distance, flashRange);
            color = Optics.shade(rgb, face(hit.getHitBlockFace()), light, distance, maxDistance, fog);
            best = distance;
            break;
        }
        for (Target target : targets) {
            if(target.figure()!=null) {
                var hit=target.figure().hit(origin,direction,best);
                if(hit!=null&&hit.distance()<best) {
                    best=hit.distance();
                    color=Optics.shade(hit.color(),face(hit.face()),Optics.flash(Optics.light(15,0,Math.max(daylight,.5)),best,flashRange),best,maxDistance,fog);
                }
                continue;
            }
            RayTraceResult entity = target.box().rayTrace(origin, direction, best);
            if (entity == null) continue;
            double distance = entity.getHitPosition().distance(origin);
            if (distance >= best) continue;
            best = distance;
            color = Optics.shade(target.color(), face(entity.getHitBlockFace()), Optics.light(15, 0, Math.max(daylight, 0.5)), distance, maxDistance, fog);
        }
        return color < 0 ? fog : color;
    }
}
