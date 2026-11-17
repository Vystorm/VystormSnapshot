package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Postcards: a player sends a copy of a photo with a short greeting to another player. Online recipients get the card
 * at once; for offline ones it waits in {@code postcards.yml} and arrives on the next join (with a Core inbox note).
 * The card is a copy of the photo map, so it costs no extra map. Pure storage, server thread.
 */
final class Postcards {
    static final int MAX_PENDING = 27, MAX_TEXT = 80;

    record Card(int map, UUID from, String fromName, String text, long sent) {}

    private final Path file;
    private final AtomicFilesInterface files;
    private final Map<UUID, List<Card>> pending = new HashMap<>();
    private final Map<UUID, List<Long>> sent = new HashMap<>();

    Postcards(Path file, AtomicFilesInterface files) {
        this.file = file;
        this.files = files;
        if (!Files.exists(file)) return;
        // a corrupt postcards.yml is set aside by Core instead of being overwritten by the next save
        YamlConfiguration yaml = files.readYaml(file, java.util.logging.Logger.getLogger("VystormSnapshot"));
        if (yaml == null) return;
        ConfigurationSection all = yaml.getConfigurationSection("pending");
        if (all != null) for (String key : all.getKeys(false)) try {
            List<Card> cards = new ArrayList<>();
            for (Map<?, ?> raw : all.getMapList(key)) {
                Object map = raw.get("map"), from = raw.get("from"), name = raw.get("name"), text = raw.get("text"), at = raw.get("sent");
                if (map instanceof Number m && from instanceof String f && name instanceof String n && text instanceof String t && at instanceof Number s)
                    cards.add(new Card(m.intValue(), UUID.fromString(f), n, t, s.longValue()));
            }
            if (!cards.isEmpty()) pending.put(UUID.fromString(key), cards);
        } catch (IllegalArgumentException ignored) { /* damaged row */ }
    }

    /** Clean greeting: no colour codes or line breaks, at most {@link #MAX_TEXT} characters; null if unusable. */
    static String clean(String raw) {
        if (raw == null) return "";
        String text = raw.replaceAll("\\u00a7.?", "").replaceAll("[\\p{Cntrl}\\u2028\\u2029]", " ").replaceAll("\\s{2,}", " ").strip();
        return text.codePointCount(0, text.length()) > MAX_TEXT ? null : text;
    }

    /** Fewer than {@code perDay} cards in the last 24 hours. */
    static boolean allowed(List<Long> times, long now, int perDay) {
        return times.stream().filter(t -> now - t < 86_400_000L).count() < perDay;
    }

    boolean mayBeSent(UUID sender, long now, int perDay) { return allowed(sent.getOrDefault(sender, List.of()), now, perDay); }

    void sentBy(UUID sender, long now) {
        List<Long> times = sent.computeIfAbsent(sender, k -> new ArrayList<>());
        times.removeIf(t -> now - t >= 86_400_000L);
        times.add(now);
    }

    int pending(UUID player) { return pending.getOrDefault(player, List.of()).size(); }

    /** Keeps a card for later; false when the recipient's mailbox is full or the file could not be written. */
    boolean keep(UUID to, Card card) {
        List<Card> cards = pending.computeIfAbsent(to, k -> new ArrayList<>());
        if (cards.size() >= MAX_PENDING) return false;
        cards.add(card);
        if (save()) return true;
        cards.remove(card);
        if (cards.isEmpty()) pending.remove(to);
        return false;
    }

    /** Takes up to {@code max} waiting cards (oldest first). */
    List<Card> take(UUID to, int max) {
        List<Card> cards = pending.get(to);
        if (cards == null || max <= 0) return List.of();
        List<Card> out = new ArrayList<>(cards.subList(0, Math.min(max, cards.size())));
        cards.subList(0, out.size()).clear();
        if (cards.isEmpty()) pending.remove(to);
        save();
        return out;
    }

    private boolean save() {
        YamlConfiguration y = new YamlConfiguration();
        y.createSection("pending");
        pending.forEach((to, cards) -> y.set("pending." + to, cards.stream().map(c -> Map.of("map", c.map(), "from", c.from().toString(),
                "name", c.fromName(), "text", c.text(), "sent", c.sent())).toList()));
        try { files.write(file, y); return true; }
        catch (IOException failure) { return false; }
    }
}
