package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingInfo;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * The rankings of Vystorm Snapshot in the namespace {@code snapshot} (Core 0.67.0, capability
 * {@code placeholder-rankings}). This is the only class that mentions Core's ranking types: it is loaded only after
 * {@link SnapshotPlaceholders#rankings} checked the capability, so the plugin still loads on an older Core.
 *
 * <ul>
 *   <li>{@code snapshot:photos}: photos in the player's album, the same count as {@code %vystorm_snapshot_photos%}
 *       (read from album.yml, which the plugin already keeps).</li>
 *   <li>{@code snapshot:likes}: likes on the photos the player shows in the gallery, the same count as
 *       {@code %vystorm_snapshot_gallery_likes%} (from gallery.yml).</li>
 * </ul>
 * Both answer from memory on the server thread and list offline players. Neither shows where a photo was taken.
 */
final class SnapshotRankings {
    static final String PHOTOS = "photos", LIKES = "likes";

    private SnapshotRankings() {}

    /** Declares the rankings whose feature is on and removes the others (start and every reload). */
    static void apply(Plugin owner, PlaceholderInterface registry, boolean photosOn, boolean likesOn,
                      SnapshotPlaceholders.RankingData data, UnaryOperator<String> ref) {
        if (!photosOn && !likesOn) {
            registry.namespace(SnapshotPlaceholders.NAMESPACE).ifPresent(ns -> { ns.removeRanking(PHOTOS); ns.removeRanking(LIKES); });
            return;
        }
        PlaceholderNamespace ns = registry.register(owner, SnapshotPlaceholders.NAMESPACE);
        if (photosOn) ns.ranking(PHOTOS, RankingInfo.of(ref.apply("rankings.photos.label"), ref.apply("rankings.photos.description")),
                n -> top(photos(data.albumEntries()), n));
        else ns.removeRanking(PHOTOS);
        if (likesOn) ns.ranking(LIKES, RankingInfo.of(ref.apply("rankings.likes.label"), ref.apply("rankings.likes.description")),
                n -> top(likes(data.exhibits()), n));
        else ns.removeRanking(LIKES);
    }

    /** Photos per photographer with the name of their newest photo (album order is oldest first). */
    static Map<UUID, Count> photos(Collection<Album.Entry> entries) {
        Map<UUID, Count> counts = new LinkedHashMap<>();
        for (Album.Entry entry : entries) counts.computeIfAbsent(entry.photographer(), id -> new Count()).add(entry.name(), 1);
        return counts;
    }

    /** Likes per photographer over all their photos on show; a photographer whose photos have no like yet counts 0. */
    static Map<UUID, Count> likes(Collection<Gallery.Exhibit> exhibits) {
        Map<UUID, Count> counts = new LinkedHashMap<>();
        for (Gallery.Exhibit exhibit : exhibits) counts.computeIfAbsent(exhibit.photographer, id -> new Count()).add(exhibit.name, exhibit.likes.size());
        return counts;
    }

    private static List<RankingSource.Row> top(Map<UUID, Count> counts, int n) {
        List<RankingSource.Row> rows = new ArrayList<>(counts.size());
        counts.forEach((player, count) -> rows.add(new RankingSource.Row(player, count.name, count.value)));
        rows.sort(Comparator.comparingDouble(RankingSource.Row::value).reversed());
        return rows.size() > n ? rows.subList(0, n) : rows;
    }

    static final class Count {
        String name = "?";
        long value;

        void add(String name, long amount) {
            if (name != null && !name.isBlank() && !name.equals("?")) this.name = name;
            value += amount;
        }
    }
}
