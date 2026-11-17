package at.esoren.vystormsnapshot;

import java.util.Locale;
import org.bukkit.Location;

/** Photo formats (maps across × down), tile slicing, selfie position and compass words. Pure where possible. */
final class Format {
    private Format() {}

    /** "2x1" → {2, 1}; null for anything else or sizes outside 1..4. */
    static int[] parse(String text) {
        if (text == null) return null;
        String[] parts = text.trim().toLowerCase(Locale.ROOT).split("x");
        if (parts.length != 2) return null;
        try {
            int w = Integer.parseInt(parts[0]), h = Integer.parseInt(parts[1]);
            return w >= 1 && w <= 4 && h >= 1 && h <= 4 ? new int[]{w, h} : null;
        } catch (NumberFormatException wrong) {
            return null;
        }
    }

    /** The 128×128 pixels of map ({@code tx}, {@code ty}) out of a picture {@code width} pixels wide. */
    static byte[] tile(byte[] pixels, int width, int tx, int ty) {
        byte[] out = new byte[Optics.SIZE * Optics.SIZE];
        for (int y = 0; y < Optics.SIZE; y++)
            System.arraycopy(pixels, (ty * Optics.SIZE + y) * width + tx * Optics.SIZE, out, y * Optics.SIZE, Optics.SIZE);
        return out;
    }


    /** Self-timer choices from the config: 0..30 seconds, duplicates and invalid values dropped, sorted; never empty. */
    static java.util.List<Integer> timers(java.util.List<Integer> configured) {
        java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
        for (Integer seconds : configured) if (seconds != null && seconds >= 0 && seconds <= 30) set.add(seconds);
        if (set.isEmpty()) set.add(0);
        return java.util.List.copyOf(set);
    }
    /** Camera three blocks in front of the face, looking back at the player. */
    static Location selfie(Location eye) {
        Location at = eye.clone().add(eye.getDirection().setY(0).normalize().multiply(3));
        at.setYaw(eye.getYaw() + 180);
        at.setPitch(Math.clamp(-eye.getPitch(), -30, 30));
        return at;
    }

    /** Yaw → N, NE, E … (Minecraft: yaw 0 = south). */
    static String facing(float yaw) {
        String[] names = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        return names[Math.floorMod(Math.round(yaw / 45f), 8)];
    }
}
