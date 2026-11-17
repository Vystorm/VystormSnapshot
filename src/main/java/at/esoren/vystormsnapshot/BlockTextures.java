package at.esoren.vystormsnapshot;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.BlockData;
import org.bukkit.util.Vector;

/** Reads vanilla/resource-pack assets once; rendering never opens files or downloads textures. */
final class BlockTextures {
    static final BlockTextures EMPTY = new BlockTextures(Map.of(), Map.of(), Map.of());
    private record Model(Map<String, String> textures, JsonArray elements) {}
    private record Selection(Model model, int x, int y) {}
    private final Map<String, SurfaceTexture> images;
    private final Map<String, JsonObject> models, states;
    private final Map<String, Optional<Selection>> resolved = new HashMap<>();
    private BlockTextures(Map<String, SurfaceTexture> images, Map<String, JsonObject> models, Map<String, JsonObject> states) {
        this.images = Map.copyOf(images);
        this.models = Map.copyOf(models);
        this.states = Map.copyOf(states);
    }
    int size() {
        return images.size();
    }
    static BlockTextures load(Path path) throws IOException {
        Map<String, SurfaceTexture> images = new HashMap<>();
        Map<String, JsonObject> models = new HashMap<>(), states = new HashMap<>();
        long decoded = 0, total = 0;
        int entries = 0;
        try (ZipFile zip = new ZipFile(path.toFile())) {
            var all = zip.entries();
            while (all.hasMoreElements()) {
                var entry = all.nextElement();
                String name = entry.getName();
                if (!name.startsWith("assets/minecraft/") || entry.isDirectory()) continue;
                boolean png = name.startsWith("assets/minecraft/textures/block/") && name.endsWith(".png");
                boolean model = name.startsWith("assets/minecraft/models/block/") && name.endsWith(".json");
                boolean state = name.startsWith("assets/minecraft/blockstates/") && name.endsWith(".json");
                if (!png && !model && !state) continue;
                if (++entries > 20000) throw new IOException("Too many texture assets");
                int cap = png ? 2_097_152 : 262_144;
                byte[] bytes;
                try (var in = zip.getInputStream(entry)) {
                    bytes = in.readNBytes(cap + 1);
                }
                total += bytes.length;
                if (bytes.length > cap || total > 64L * 1024 * 1024) throw new IOException("Texture pack exceeds size limit");
                if (png) {
                    SurfaceTexture image = SurfaceTexture.read(bytes, 1024);
                    decoded += (long) image.width * image.height;
                    if (decoded > 16_777_216) throw new IOException("Texture pixel budget exceeded");
                    images.put(name.substring("assets/minecraft/textures/".length(), name.length() - 4), image);
                } else {
                    try {
                        JsonObject object = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
                        (model ? models : states).put(name.substring((model ? "assets/minecraft/models/" : "assets/minecraft/blockstates/").length(), name.length() - 5), object);
                    } catch (RuntimeException invalid) {
                        throw new IOException("Invalid model asset: " + name, invalid);
                    }
                }
            }
        }
        return new BlockTextures(images, models, states);
    }
    int sample(BlockData data, BlockFace face, Vector point, int fallback) {
        return sample(data.getAsString(), face, point.getX(), point.getY(), point.getZ(), fallback);
    }
    int sample(String state, BlockFace face, double x, double y, double z, int fallback) {
        try {
            return paint(state, face, x, y, z, fallback);
        } catch (RuntimeException invalidModel) {
            return fallback;
        }
    }
    private int paint(String state, BlockFace face, double x, double y, double z, int fallback) {
        if (face == null || images.isEmpty()) return fallback;
        Optional<Selection> found = resolved.get(state);
        if (found == null) {
            if (resolved.size() >= 4096) resolved.clear();
            try {
                found = Optional.ofNullable(select(state));
            } catch (RuntimeException invalid) {
                found = Optional.empty();
            }
            resolved.put(state, found);
        }
        if (found.isEmpty()) return fallback;
        Selection selected = found.get();
        double[] p = unrotate(x - .5, y - .5, z - .5, selected.x, selected.y);
        double[] normal = unrotate(face.getModX(), face.getModY(), face.getModZ(), selected.x, selected.y);
        String side = normal[1] > .5 ? "up" : normal[1] < -.5 ? "down" : normal[0] > .5 ? "east" : normal[0] < -.5 ? "west" : normal[2] > .5 ? "south" : "north";
        x = p[0] + .5;
        y = p[1] + .5;
        z = p[2] + .5;
        double u = switch (side) {
            case "north" -> 1 - x;
            case "west" -> z;
            case "east" -> 1 - z;
            default -> x;
        };
        double v = switch (side) {
            case "up" -> z;
            case "down" -> 1 - z;
            default -> 1 - y;
        };
        int result = fallback;
        for (JsonElement element : selected.model.elements) {
            JsonObject obj = element.getAsJsonObject();
            JsonObject faces = obj.getAsJsonObject("faces");
            if (faces == null || !faces.has(side)) continue;
            double[] from = bounds(obj, "from", 0), to = bounds(obj, "to", 1);
            if (x < from[0] - .001 || x > to[0] + .001 || y < from[1] - .001 || y > to[1] + .001 || z < from[2] - .001 || z > to[2] + .001) continue;
            JsonObject f = faces.getAsJsonObject(side);
            if (!f.has("texture")) continue;
            String ref = texture(f.get("texture").getAsString(), selected.model.textures);
            SurfaceTexture image = images.get(ref);
            if (image == null) continue;
            double a = u, b = v;
            if (f.has("uv")) {
                double xx = (x - from[0]) / Math.max(.0001, to[0] - from[0]), yy = (y - from[1]) / Math.max(.0001, to[1] - from[1]), zz = (z - from[2]) / Math.max(.0001, to[2] - from[2]);
                a = switch (side) {
                    case "north" -> 1 - xx;
                    case "west" -> zz;
                    case "east" -> 1 - zz;
                    default -> xx;
                };
                b = switch (side) {
                    case "up" -> zz;
                    case "down" -> 1 - zz;
                    default -> 1 - yy;
                };
            }
            int rotation = f.has("rotation") ? Math.floorMod(f.get("rotation").getAsInt() / 90, 4) : 0;
            for (int i = 0; i < rotation; i++) {
                double old = a;
                a = 1 - b;
                b = old;
            }
            if (f.has("uv")) {
                JsonArray uv = f.getAsJsonArray("uv");
                if (uv.size() == 4) {
                    a = (uv.get(0).getAsDouble() + a * (uv.get(2).getAsDouble() - uv.get(0).getAsDouble())) / 16;
                    b = (uv.get(1).getAsDouble() + b * (uv.get(3).getAsDouble() - uv.get(1).getAsDouble())) / 16;
                }
            }
            result = SurfaceTexture.composite(image.sample(a, b), result, fallback, f.has("tintindex"));
        }
        return result;
    }
    private static double[] bounds(JsonObject obj, String key, double defaultValue) {
        if (!obj.has(key)) return new double[]{defaultValue, defaultValue, defaultValue};
        JsonArray a = obj.getAsJsonArray(key);
        return new double[]{a.get(0).getAsDouble() / 16, a.get(1).getAsDouble() / 16, a.get(2).getAsDouble() / 16};
    }
    private Selection select(String full) {
        int bracket = full.indexOf('[');
        String name = key(bracket < 0 ? full : full.substring(0, bracket));
        Map<String, String> props = new HashMap<>();
        if (bracket >= 0) for (String pair : full.substring(bracket + 1, full.length() - 1).split(",")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) props.put(parts[0], parts[1]);
        }
        JsonObject definition = states.get(name), variant = null;
        if (definition != null && definition.has("variants")) for (var entry : definition.getAsJsonObject("variants").entrySet()) {
            boolean match = true;
            for (String term : entry.getKey().split(",")) if (!term.isEmpty()) {
                String[] pair = term.split("=", 2);
                if (pair.length != 2 || !Arrays.asList(pair[1].split("\\|")).contains(props.get(pair[0]))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                JsonElement value = entry.getValue();
                variant = (value.isJsonArray() ? value.getAsJsonArray().get(0) : value).getAsJsonObject();
                break;
            }
        }
        String modelName = variant != null && variant.has("model") ? variant.get("model").getAsString() : "block/" + name;
        Model model = model(key(modelName), new HashSet<>());
        if (model == null) return null;
        return new Selection(model, variant != null && variant.has("x") ? variant.get("x").getAsInt() : 0, variant != null && variant.has("y") ? variant.get("y").getAsInt() : 0);
    }
    private Model model(String name, Set<String> seen) {
        if (seen.size() >= 16 || !seen.add(name)) return null;
        JsonObject obj = models.get(name);
        if (obj == null) return null;
        Model parent = obj.has("parent") ? model(key(obj.get("parent").getAsString()), seen) : null;
        Map<String, String> textures = new HashMap<>();
        if (parent != null) textures.putAll(parent.textures);
        if (obj.has("textures")) obj.getAsJsonObject("textures").entrySet().forEach(e -> textures.put(e.getKey(), e.getValue().getAsString()));
        return new Model(textures, obj.has("elements") ? obj.getAsJsonArray("elements") : parent == null ? new JsonArray() : parent.elements);
    }
    private static String texture(String ref, Map<String, String> definitions) {
        Set<String> seen = new HashSet<>();
        while (ref.startsWith("#") && seen.size() < 16 && seen.add(ref)) ref = definitions.getOrDefault(ref.substring(1), "");
        return key(ref);
    }
    private static String key(String name) {
        return name.startsWith("minecraft:") ? name.substring(10) : name;
    }
    private static double[] unrotate(double x, double y, double z, int rx, int ry) {
        for (int i = 0; i < Math.floorMod(ry / 90, 4); i++) {
            double old = x;
            x = -z;
            z = old;
        }
        for (int i = 0; i < Math.floorMod(rx / 90, 4); i++) {
            double old = y;
            y = z;
            z = -old;
        }
        return new double[]{x, y, z};
    }
}
