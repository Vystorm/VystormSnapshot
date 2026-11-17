package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Photo contest ({@code photos/contest.yml}): an admin starts a contest with a theme and an end time; every player
 * enters one photo from the gallery and everyone votes once per photo (not for their own). The photo with the most
 * votes wins; on a tie the earlier entry wins. The last result is kept until the next contest starts. Server thread.
 */
final class PhotoContest {
    static final class Entry {
        final int map;
        final UUID photographer;
        final String name;
        final long entered;
        final Set<UUID> votes = new LinkedHashSet<>();
        Entry(int map, UUID photographer, String name, long entered) { this.map = map; this.photographer = photographer; this.name = name; this.entered = entered; }
    }
    /** Winner of a finished contest (map -1 = nobody entered). */
    record Result(String theme, int map, UUID photographer, String name, int votes) {}

    private final Path file;
    private final AtomicFilesInterface files;
    private String theme;
    private long ends;
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private Result last;

    PhotoContest(Path file, AtomicFilesInterface files) {
        this.file = file;
        this.files = files;
        load();
    }

    boolean running(long now) { return theme != null && now < ends; }
    /** Started and its end time has passed, but not yet closed. */
    boolean due(long now) { return theme != null && now >= ends; }
    String theme() { return theme; }
    long ends() { return ends; }
    Optional<Result> last() { return Optional.ofNullable(last); }
    Collection<Entry> entries() { return Collections.unmodifiableCollection(entries.values()); }

    /** Entries by votes (most first), then by entry time. */
    List<Entry> ranking() {
        List<Entry> list = new ArrayList<>(entries.values());
        list.sort(Comparator.comparingInt((Entry e) -> e.votes.size()).reversed().thenComparingLong(e -> e.entered));
        return list;
    }

    /** False when a contest is already running. */
    boolean start(String newTheme, long now, long durationMillis) {
        if (theme != null) return false;
        theme = newTheme;
        ends = now + durationMillis;
        entries.clear();
        save();
        return true;
    }

    /** {@code ok}, {@code closed}, {@code taken} (photo entered by someone else). Re-entering replaces the own entry. */
    String enter(int map, UUID photographer, String name, long now) {
        if (!running(now)) return "closed";
        for (Entry e : entries.values()) if (e.map == map && !e.photographer.equals(photographer)) return "taken";
        Entry old = entries.get(photographer);
        if (old != null && old.map == map) return "ok";
        entries.put(photographer, new Entry(map, photographer, name, now));
        save();
        return "ok";
    }

    /** {@code voted}, {@code unvoted}, {@code own}, {@code missing}, {@code closed}. */
    String vote(int map, UUID voter, long now) {
        if (!running(now)) return "closed";
        for (Entry e : entries.values()) {
            if (e.map != map) continue;
            if (e.photographer.equals(voter)) return "own";
            String result = e.votes.remove(voter) ? "unvoted" : e.votes.add(voter) ? "voted" : "voted";
            save();
            return result;
        }
        return "missing";
    }

    /** Closes the contest (also before its end, by an admin); returns the result. */
    Result close() {
        if (theme == null) return null;
        List<Entry> ranking = ranking();
        Entry winner = ranking.isEmpty() ? null : ranking.getFirst();
        last = winner == null ? new Result(theme, -1, null, "", 0) : new Result(theme, winner.map, winner.photographer, winner.name, winner.votes.size());
        theme = null;
        ends = 0;
        entries.clear();
        save();
        return last;
    }

    private void load() {
        if (file == null || !Files.exists(file)) return;
        // a corrupt contest.yml is set aside by Core instead of being overwritten by the next save
        YamlConfiguration yaml = files.readYaml(file, java.util.logging.Logger.getLogger("VystormSnapshot"));
        if (yaml == null) return;
        theme = yaml.getString("theme");
        ends = yaml.getLong("ends");
        ConfigurationSection list = yaml.getConfigurationSection("entries");
        if (list != null) for (String key : list.getKeys(false)) {
            ConfigurationSection s = list.getConfigurationSection(key);
            try {
                UUID photographer = UUID.fromString(key);
                Entry e = new Entry(s.getInt("map"), photographer, s.getString("name", "?"), s.getLong("entered"));
                for (String v : s.getStringList("votes")) e.votes.add(UUID.fromString(v));
                entries.put(photographer, e);
            } catch (RuntimeException skipped) { /* broken entry */ }
        }
        if (yaml.isConfigurationSection("last")) {
            ConfigurationSection s = yaml.getConfigurationSection("last");
            String who = s.getString("photographer");
            try { last = new Result(s.getString("theme", ""), s.getInt("map", -1), who == null ? null : UUID.fromString(who), s.getString("name", ""), s.getInt("votes")); }
            catch (RuntimeException skipped) { last = null; }
        }
    }

    private void save() {
        if (file == null) return;
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Photo contest (/snapshot contest). Written by VystormSnapshot."));
        if (theme != null) { yaml.set("theme", theme); yaml.set("ends", ends); }
        for (Entry e : entries.values()) {
            String k = "entries." + e.photographer;
            yaml.set(k + ".map", e.map);
            yaml.set(k + ".name", e.name);
            yaml.set(k + ".entered", e.entered);
            yaml.set(k + ".votes", e.votes.stream().map(UUID::toString).toList());
        }
        if (last != null) {
            yaml.set("last.theme", last.theme());
            yaml.set("last.map", last.map());
            if (last.photographer() != null) yaml.set("last.photographer", last.photographer().toString());
            yaml.set("last.name", last.name());
            yaml.set("last.votes", last.votes());
        }
        try { files.write(file, yaml); }
        catch (IOException | RuntimeException failure) { java.util.logging.Logger.getLogger("VystormSnapshot").warning("contest.yml not saved: " + failure); }
    }
}
