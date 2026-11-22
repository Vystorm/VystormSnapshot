package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderRanking;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.RankingSource;
import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import java.io.File;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The rankings snapshot:photos and snapshot:likes (Core 0.67.0) from a small album and gallery fixture. */
class SnapshotRankingsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final UUID ALEX = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID SAM = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID KIM = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    @TempDir Path dir;
    private Album album;
    private Gallery gallery;
    private PlaceholderTesting placeholders;
    private Plugin plugin;

    @BeforeEach void fixture() {
        album = new Album(dir.resolve("album.yml"), Logger.getAnonymousLogger(), AtomicFilesInterface.DEFAULT, PlatformCompatInterface.DEFAULT);
        gallery = new Gallery(dir.resolve("gallery.yml"), Logger.getAnonymousLogger(), AtomicFilesInterface.DEFAULT, PlatformCompatInterface.DEFAULT);
        // Alex: 3 maps (one single photo, one 2x1 photo), Sam: 1, Kim: 2; nobody is online in a unit test
        album.add(new Album.Entry(1, ALEX, "Alex", "world", "", "2026-11-01", ""));
        album.add(new Album.Entry(2, SAM, "Sam", "world", "", "2026-11-01", ""));
        album.add(new Album.Entry(3, ALEX, "Alex", "world", "", "2026-11-02", "1/2"));
        album.add(new Album.Entry(4, ALEX, "Alex", "world", "", "2026-11-02", "2/2"));
        album.add(new Album.Entry(5, KIM, "Kim", "world", "", "2026-11-03", ""));
        album.add(new Album.Entry(6, KIM, "Kimberly", "world", "", "2026-11-04", ""));
        gallery.publish(1, ALEX, "Alex", "2026-11-01", 1);
        gallery.publish(2, SAM, "Sam", "2026-11-01", 2);
        gallery.publish(5, KIM, "Kim", "2026-11-03", 3);
        gallery.like(2, ALEX);
        gallery.like(2, KIM);
        gallery.like(1, SAM);
        placeholders = PlaceholderTesting.detached();
        plugin = PlaceholderTesting.plugin("VystormSnapshot");
    }

    private SnapshotPlaceholders.RankingData data() {
        return new SnapshotPlaceholders.RankingData() {
            @Override public Collection<Album.Entry> albumEntries() { return album.all(); }
            @Override public Collection<Gallery.Exhibit> exhibits() { return gallery.all(); }
        };
    }

    private void apply(boolean core, Set<Feature> off) {
        SnapshotPlaceholders.rankings(placeholders.placeholders(), plugin, core, f -> !off.contains(f), data(), key -> "{lang:snapshot:" + key + "}", null);
    }

    private List<String> ids() { return placeholders.rankings().stream().map(PlaceholderRanking::id).sorted().toList(); }

    @Test void givenAllOn_whenDeclared_thenBothRankingsWithLabelsAndDescendingOrder() {
        apply(true, Set.of());
        assertEquals(List.of("snapshot:likes", "snapshot:photos"), ids());
        PlaceholderRanking photos = placeholders.ranking("snapshot:photos").orElseThrow();
        assertEquals("{lang:snapshot:rankings.photos.label}", photos.info().label());
        assertEquals("{lang:snapshot:rankings.photos.description}", photos.info().description());
        assertEquals("DESCENDING", photos.info().order().name());
        assertEquals(List.of(), placeholders.warnings());
    }

    @Test void givenTheFixture_whenTopIsRead_thenPhotosAndLikesCountedPerPlayerOfflineIncluded() {
        apply(true, Set.of());
        List<RankingSource.Row> photos = placeholders.top("snapshot:photos", 10);
        assertEquals(List.of(ALEX, KIM, SAM), photos.stream().map(RankingSource.Row::player).toList());
        assertEquals(List.of(3.0, 2.0, 1.0), photos.stream().map(RankingSource.Row::value).toList());
        assertEquals("Kimberly", photos.get(1).name(), "the name of the newest photo");
        List<RankingSource.Row> likes = placeholders.top("snapshot:likes", 10);
        assertEquals(List.of(SAM, ALEX, KIM), likes.stream().map(RankingSource.Row::player).toList());
        assertEquals(List.of(2.0, 1.0, 0.0), likes.stream().map(RankingSource.Row::value).toList());
        assertEquals(1, placeholders.top("snapshot:photos", 1).size());
        assertEquals("Alex", placeholders.resolve(null, "%vystorm_snapshot_top_photos_1_name%"));
        assertEquals("3", placeholders.resolve(null, "%vystorm_snapshot_top_photos_1_value%"));
        assertEquals("Sam", placeholders.resolve(null, "{snapshot:top_likes:1:name}"));
        assertEquals("2", placeholders.resolve(null, "%vystorm_snapshot_top_likes_1_raw%"));
        assertEquals("", placeholders.resolve(null, "%vystorm_snapshot_top_photos_4_name%"), "a rank nobody holds is empty");
        assertEquals(List.of(), placeholders.warnings());
    }

    @Test void givenANewPhoto_whenTopIsReadAgain_thenTheRankingFollowsWithoutRedeclaring() {
        apply(true, Set.of());
        album.add(new Album.Entry(7, SAM, "Sam", "world", "", "2026-11-05", ""));
        album.add(new Album.Entry(8, SAM, "Sam", "world", "", "2026-11-05", ""));
        album.add(new Album.Entry(9, SAM, "Sam", "world", "", "2026-11-05", ""));
        placeholders.advanceTicks(1);
        assertEquals(SAM, placeholders.top("snapshot:photos", 1).getFirst().player());
    }

    @Test void givenRankingsSwitchedOff_whenApplied_thenNothingDeclaredAndAReloadRemovesThem() {
        apply(true, Set.of(Feature.RANKINGS));
        assertEquals(List.of(), ids());
        apply(true, Set.of());
        assertEquals(2, ids().size());
        apply(true, Set.of(Feature.RANKINGS));
        assertEquals(List.of(), ids(), "a reload with rankings.enabled off removes both");
    }

    @Test void givenTheGalleryOrAlbumOff_whenApplied_thenOnlyThatRankingIsGone() {
        apply(true, Set.of(Feature.GALLERY));
        assertEquals(List.of("snapshot:photos"), ids());
        apply(true, Set.of(Feature.ALBUM));
        assertEquals(List.of("snapshot:likes"), ids());
        Feature.Switches cameraOff = Feature.Switches.without(Feature.CAMERA);
        SnapshotPlaceholders.rankings(placeholders.placeholders(), plugin, true, cameraOff::on, data(), k -> k, null);
        assertEquals(List.of(), ids(), "camera.enabled off (the whole plugin) switches the rankings off too");
    }

    @Test void givenACoreWithoutTheCapability_whenApplied_thenNoDeclarationAndNoError() {
        assertDoesNotThrow(() -> apply(false, Set.of()));
        assertEquals(List.of(), ids());
        assertDoesNotThrow(() -> SnapshotPlaceholders.rankings(null, plugin, true, f -> true, data(), k -> k, null));
    }

    @Test void givenTheNamespaceOwnedByAnotherPlugin_whenApplied_thenRefusedQuietly() {
        placeholders.register(PlaceholderTesting.plugin("Other"), "snapshot");
        assertDoesNotThrow(() -> apply(true, Set.of()));
        assertEquals(List.of(), ids());
    }

    @Test void givenEveryLanguage_whenRead_thenRankingsHaveLabelsAndDescriptions() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            for (String key : List.of("photos", "likes"))
                for (String part : List.of("label", "description"))
                    assertFalse(lang.getString("rankings." + key + "." + part, "").isBlank(), code + " rankings." + key + "." + part);
            assertFalse(lang.getString(Feature.RANKINGS.label(), "").isBlank(), code + " switch name");
            assertFalse(lang.getString(Feature.RANKINGS.description(), "").isBlank(), code + " switch description");
        }
    }
}
