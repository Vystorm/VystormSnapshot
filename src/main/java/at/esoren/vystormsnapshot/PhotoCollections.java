package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/** Named, private-by-default collections. Publish a new state only after its atomic write succeeds. */
final class PhotoCollections {
    record Collection(UUID id, UUID owner, String name, String title, List<Integer> photos, Set<UUID> readers) {
        Collection { photos = List.copyOf(photos); readers = Set.copyOf(readers); }
        boolean visible(UUID viewer) { return owner.equals(viewer) || readers.contains(viewer); }
    }
    enum Result { SAVED, MISSING, DENIED, INVALID, LIMIT, UNAVAILABLE }
    private final Path file;
    private final AtomicFilesInterface files;
    private final Logger log;
    private Map<UUID, Collection> collections = new LinkedHashMap<>();
    private boolean readOnly;
    PhotoCollections(Path file, Logger log, AtomicFilesInterface files) {
        this.file = file; this.files = files; this.log = log;
        if (!Files.exists(file)) return;
        try {
            YamlConfiguration y = new YamlConfiguration(); y.load(file.toFile());
            if (!y.isConfigurationSection("collections")) throw new IOException("collections section missing");
            for (String key : y.getConfigurationSection("collections").getKeys(false)) {
                String p = "collections." + key + ".";
                UUID id = UUID.fromString(key), owner = UUID.fromString(y.getString(p + "owner", ""));
                String name = y.getString(p + "name", ""), title = y.getString(p + "title", "");
                if (!(y.get(p + "photos") instanceof List<?> rawPhotos) || rawPhotos.stream().anyMatch(n -> !(n instanceof Integer))
                        || !(y.get(p + "readers") instanceof List<?> rawReaders) || rawReaders.stream().anyMatch(r -> !(r instanceof String)))
                    throw new IOException("invalid collection lists " + key);
                List<Integer> photos = y.getIntegerList(p + "photos");
                Set<UUID> readers = new HashSet<>();
                for (String reader : y.getStringList(p + "readers")) readers.add(UUID.fromString(reader));
                if (!validName(name) || !validText(title, 64) || photos.size() > 200 || readers.size() > 32
                        || photos.stream().anyMatch(n -> n < 0) || new HashSet<>(photos).size() != photos.size()
                        || own(owner, name) != null || collections.values().stream().filter(c -> c.owner().equals(owner)).count() >= 20) throw new IOException("invalid collection " + key);
                collections.put(id, new Collection(id, owner, name, title, photos, readers));
            }
        } catch (Exception failure) {
            collections.clear(); readOnly = true;
            log.warning("Cannot read photo collections; original file is retained and changes disabled: " + failure.getMessage());
        }
    }
    static boolean validName(String name) { return name != null && name.matches("[A-Za-z0-9_-]{1,24}"); }
    static boolean validText(String text, int max) {
        return text != null && !text.isBlank() && text.codePointCount(0, text.length()) <= max
                && text.codePoints().noneMatch(c -> Character.isISOControl(c) || c == 0xa7 || c == 0x2028 || c == 0x2029);
    }
    Collection get(UUID id) { return collections.get(id); }
    Collection own(UUID owner, String name) {
        return collections.values().stream().filter(c -> c.owner().equals(owner) && c.name().equalsIgnoreCase(name)).findFirst().orElse(null);
    }
    List<Collection> visible(UUID viewer) { return collections.values().stream().filter(c -> c.visible(viewer)).toList(); }
    Result create(UUID owner, String name) {
        if (readOnly) return Result.UNAVAILABLE;
        if (!validName(name) || own(owner, name) != null) return Result.INVALID;
        if (collections.values().stream().filter(c -> c.owner().equals(owner)).count() >= 20) return Result.LIMIT;
        return store(new Collection(UUID.randomUUID(), owner, name, name, List.of(), Set.of()), false);
    }
    Result title(UUID actor, UUID id, String title) {
        Collection c = get(id); Result check = editable(actor, c); if (check != null) return check;
        if (!validText(title, 64)) return Result.INVALID;
        return store(new Collection(id, c.owner(), c.name(), title.strip(), c.photos(), c.readers()), false);
    }
    Result photo(UUID actor, UUID id, int firstMap, UUID photographer, boolean add) {
        Collection c = get(id); Result check = editable(actor, c); if (check != null) return check;
        if (firstMap < 0 || !actor.equals(photographer)) return Result.DENIED;
        List<Integer> photos = new ArrayList<>(c.photos());
        if (add && !photos.contains(firstMap)) {
            if (photos.size() >= 200) return Result.LIMIT;
            photos.add(firstMap);
        } else if (!add) photos.remove(Integer.valueOf(firstMap));
        return store(new Collection(id, c.owner(), c.name(), c.title(), photos, c.readers()), false);
    }
    Result share(UUID actor, UUID id, UUID reader, boolean add) {
        Collection c = get(id); Result check = editable(actor, c); if (check != null) return check;
        if (reader == null || reader.equals(actor)) return Result.INVALID;
        Set<UUID> readers = new HashSet<>(c.readers());
        if (add) { if (!readers.contains(reader) && readers.size() >= 32) return Result.LIMIT; readers.add(reader); }
        else readers.remove(reader);
        return store(new Collection(id, c.owner(), c.name(), c.title(), c.photos(), readers), false);
    }
    Result delete(UUID actor, UUID id) {
        Collection c = get(id); Result check = editable(actor, c); return check == null ? store(c, true) : check;
    }
    private Result editable(UUID actor, Collection c) {
        if (readOnly) return Result.UNAVAILABLE;
        return c == null ? Result.MISSING : !c.owner().equals(actor) ? Result.DENIED : null;
    }
    private Result store(Collection replacement, boolean remove) {
        if (readOnly) return Result.UNAVAILABLE;
        Map<UUID, Collection> next = new LinkedHashMap<>(collections);
        if (remove) next.remove(replacement.id()); else next.put(replacement.id(), replacement);
        YamlConfiguration y = new YamlConfiguration(); y.createSection("collections");
        for (Collection c : next.values()) {
            String p = "collections." + c.id() + ".";
            y.set(p + "owner", c.owner().toString()); y.set(p + "name", c.name()); y.set(p + "title", c.title());
            y.set(p + "photos", c.photos()); y.set(p + "readers", c.readers().stream().map(UUID::toString).sorted().toList());
        }
        try { files.write(file, y); collections = next; return Result.SAVED; }
        catch (IOException failure) { log.warning("Photo collections were not saved: " + failure.getMessage()); return Result.UNAVAILABLE; }
    }
}
