package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A config.yml with a YAML error is never saved over, neither at start nor on /snapshot reload. */
class BrokenConfigTest {
    static final String BROKEN = "camera:\n  enabled: true\n  material: SPYGLASS\n film PAPER\ncooldown-seconds: 5\n";

    @TempDir Path folder;
    final List<LogRecord> logged = new ArrayList<>();

    /** The plugin with Bukkit's JavaPlugin config behaviour: reloadConfig() swallows parse errors and leaves an empty config. */
    SnapshotPlugin plugin() {
        SnapshotPlugin plugin = mock(SnapshotPlugin.class, CALLS_REAL_METHODS);
        FileConfiguration[] config = {null};
        File file = folder.resolve("config.yml").toFile();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            @Override public void publish(LogRecord record) { logged.add(record); }
            @Override public void flush() { }
            @Override public void close() { }
        });
        doReturn(folder.toFile()).when(plugin).getDataFolder();
        doReturn(logger).when(plugin).getLogger();
        doAnswer(call -> { config[0] = bukkitLoad(file); return null; }).when(plugin).reloadConfig();
        doAnswer(call -> { if (config[0] == null) config[0] = bukkitLoad(file); return config[0]; }).when(plugin).getConfig();
        doAnswer(call -> { config[0].save(file); return null; }).when(plugin).saveConfig();
        return plugin;
    }

    /** YamlConfiguration.loadConfiguration without a server: a parse error leaves an empty config (Bukkit only logs it). */
    static YamlConfiguration bukkitLoad(File file) {
        YamlConfiguration config = new YamlConfiguration();
        try { config.load(file); } catch (Exception ignored) { }
        return config;
    }

    @Test void givenBrokenConfig_whenStartAndReloadReadIt_thenTheFileStaysByteIdenticalAndTheErrorIsLoggedOnce() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        byte[] before = Files.readAllBytes(file);
        SnapshotPlugin plugin = plugin();
        for (boolean reload : new boolean[]{false, true}) {
            logged.clear();
            String problem = SnapshotPlugin.readConfig(plugin, reload);
            assertNotNull(problem, "the error is reported");
            assertArrayEquals(before, Files.readAllBytes(file), "config.yml was changed (reload=" + reload + ")");
            assertEquals(1, logged.stream().filter(r -> r.getLevel() == Level.SEVERE).count(), "one SEVERE line per load");
            assertTrue(logged.getFirst().getMessage().contains("line 4, column"), logged.getFirst().getMessage());
        }
        verify(plugin, never()).saveConfig();
    }

    @Test void givenValidConfigWithoutCaptionLength_whenRead_thenTheDefaultIsStillAdded() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, "cooldown-seconds: 5\n", StandardCharsets.UTF_8);
        assertNull(SnapshotPlugin.readConfig(plugin(), false));
        assertEquals(80, YamlConfiguration.loadConfiguration(file.toFile()).getInt("captions.max-length"));
    }

    @Test void givenBrokenConfig_whenAdminReloads_thenTheAnswerNamesLineAndColumnAndTheSettingsStay() throws Exception {
        Path file = folder.resolve("config.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        SnapshotPlugin plugin = plugin();
        Lang lang = mock(Lang.class);
        set(plugin, "lang", lang);
        set(plugin, "configLoaded", true);
        set(plugin, "captionMax", 42);
        CommandSender admin = mock(CommandSender.class);
        plugin.reload(admin);
        verify(lang).send(eq(admin), eq("config-error"), eq("problem"), argThat(p -> p.toString().startsWith("line 4, column ")));
        verify(lang, never()).send(admin, "reloaded");
        assertEquals(42, get(plugin, "captionMax"), "the last good settings stay active");
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn"))
            assertTrue(YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml")).getString("config-error", "").contains("{problem}"), code);
    }

    @Test void givenBrokenYaml_whenDescribed_thenLineColumnAndProblemAreNamed() throws Exception {
        Path file = folder.resolve("data.yml");
        Files.writeString(file, BROKEN, StandardCharsets.UTF_8);
        String problem = YamlCheck.problem(file.toFile());
        assertTrue(problem.matches("line 4, column \\d+: .+"), problem);
        assertNull(YamlCheck.problem(folder.resolve("missing.yml").toFile()));
        Files.writeString(file, "a: 1\n", StandardCharsets.UTF_8);
        assertNull(YamlCheck.problem(file.toFile()));
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = SnapshotPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = SnapshotPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
