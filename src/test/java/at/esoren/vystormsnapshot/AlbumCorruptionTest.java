package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlbumCorruptionTest {
    @TempDir Path directory;
    private Album.Entry photo() { return new Album.Entry(2, UUID.randomUUID(), "Alex", "world", "", "2026-11-05", ""); }

    @Test void malformedRootStaysUnchangedWhenAddingAPhoto() throws Exception {
        Path path = directory.resolve("album.yml");
        String before = "photos: [keep, me]\n";
        Files.writeString(path, before);
        new Album(path, Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT).add(photo());
        assertEquals(before, Files.readString(path));
    }

    @Test void invalidChildDataSurvivesUnrelatedPhotoWrites() throws Exception {
        Path path = directory.resolve("album.yml");
        Files.writeString(path, "photos:\n  damaged: [keep, me]\n  '1':\n    by: invalid-uuid\n    custom:\n      nested: [original, data]\n");
        Album album = new Album(path, Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        album.add(photo());
        var yaml = YamlConfiguration.loadConfiguration(path.toFile());
        assertEquals(List.of("keep", "me"), yaml.getStringList("photos.damaged"));
        assertEquals(List.of("original", "data"), yaml.getStringList("photos.1.custom.nested"));
        assertEquals("invalid-uuid", yaml.getString("photos.1.by"));
        assertNotNull(new Album(path, Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT).entry(2));
    }
}
