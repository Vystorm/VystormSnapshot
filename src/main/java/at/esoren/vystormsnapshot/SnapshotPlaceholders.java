package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderNamespace;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.bukkit.plugin.Plugin;

/**
 * The values of Vystorm Snapshot in Core's placeholder registry, namespace {@code snapshot}. Album, gallery,
 * collections and contest live in plain maps, so every value is answered on the server thread only. A value of a
 * switched-off feature is empty. The contest theme is admin text and shown as plain text.
 *
 * <pre>
 * %vystorm_snapshot_photos%          {snapshot:photos}          photos in the player's album
 * %vystorm_snapshot_gallery_shown%, _gallery_max, _gallery_likes, _collections
 * %vystorm_snapshot_contest_running%, _contest_theme, _contest_ends_minutes, _contest_entries
 * %vystorm_snapshot_camera_cooldown% seconds until the next photo
 * </pre>
 */
final class SnapshotPlaceholders {
    static final String NAMESPACE = "snapshot";

    /** What the values read, at call time. */
    interface Source {
        boolean on(Feature feature);
        int photos(UUID player);
        Gallery gallery();
        int galleryMax();
        long collections(UUID player);
        PhotoContest contest();
        long cooldownLeft(UUID player, long now);
    }

    private SnapshotPlaceholders() {}

    static PlaceholderNamespace register(PlaceholderInterface registry, Plugin owner, Source source, UnaryOperator<String> describe) {
        PlaceholderNamespace ns = registry.register(owner, NAMESPACE);
        ns.value("photos", describe.apply("placeholder.photos"), viewer -> source.on(Feature.ALBUM) ? source.photos(viewer.getUniqueId()) : "");
        ns.value("gallery_shown", describe.apply("placeholder.gallery_shown"), viewer -> gallery(source) ? source.gallery().count(viewer.getUniqueId()) : "");
        ns.global("gallery_max", describe.apply("placeholder.gallery_max"), () -> gallery(source) ? source.galleryMax() : "");
        ns.value("gallery_likes", describe.apply("placeholder.gallery_likes"), viewer -> gallery(source)
                ? source.gallery().order(false, viewer.getUniqueId()).stream().mapToInt(e -> e.likes.size()).sum() : "");
        ns.value("collections", describe.apply("placeholder.collections"), viewer -> source.on(Feature.COLLECTIONS) ? source.collections(viewer.getUniqueId()) : "");
        ns.global("contest_running", describe.apply("placeholder.contest_running"), () -> contest(source) ? source.contest().running(System.currentTimeMillis()) : "");
        ns.global("contest_theme", describe.apply("placeholder.contest_theme"), () -> {
            if (!contest(source) || !source.contest().running(System.currentTimeMillis())) return "";
            return source.contest().theme();
        });
        ns.global("contest_ends_minutes", describe.apply("placeholder.contest_ends_minutes"), () -> {
            if (!contest(source)) return "";
            long now = System.currentTimeMillis();
            return source.contest().running(now) ? Math.max(0, (source.contest().ends() - now) / 60_000) : 0;
        });
        ns.global("contest_entries", describe.apply("placeholder.contest_entries"), () -> {
            if (!contest(source)) return "";
            return source.contest().running(System.currentTimeMillis()) ? source.contest().entries().size() : 0;
        });
        ns.value("camera_cooldown", describe.apply("placeholder.camera_cooldown"), viewer -> source.on(Feature.CAMERA)
                ? (source.cooldownLeft(viewer.getUniqueId(), System.currentTimeMillis()) + 999) / 1000 : "");
        return ns;
    }

    /** What the rankings read, at call time (server thread, from memory). */
    interface RankingData {
        java.util.Collection<Album.Entry> albumEntries();
        java.util.Collection<Gallery.Exhibit> exhibits();
    }

    /**
     * Declares {@code snapshot:photos} (album on) and {@code snapshot:likes} (gallery on) while {@code rankings.enabled}
     * is on, and removes them when a reload switched them off. {@code coreHasRankings} is
     * {@code CoreApi.has("placeholder-rankings")}: without it nothing is declared and {@link SnapshotRankings}, the only
     * class that mentions Core's ranking types, is never loaded. A refusal (another plugin owns the namespace, an older
     * Core) is logged and the plugin keeps running.
     */
    static void rankings(PlaceholderInterface registry, Plugin owner, boolean coreHasRankings, java.util.function.Predicate<Feature> on,
                         RankingData data, UnaryOperator<String> ref, java.util.logging.Logger logger) {
        if (!coreHasRankings || registry == null) return;
        boolean ranked = on.test(Feature.RANKINGS);
        try { SnapshotRankings.apply(owner, registry, ranked && on.test(Feature.ALBUM), ranked && on.test(Feature.GALLERY), data, ref); }
        catch (RuntimeException | LinkageError refused) { if (logger != null) logger.warning("Rankings not offered: " + refused); }
    }

    private static boolean gallery(Source source) { return source.on(Feature.GALLERY) && source.gallery() != null; }

    private static boolean contest(Source source) { return source.on(Feature.CONTEST) && source.contest() != null; }
}
