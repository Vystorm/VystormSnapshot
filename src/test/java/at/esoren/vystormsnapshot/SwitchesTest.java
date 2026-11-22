package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderTesting;
import Vystorm.vystorm_core._uiMethods.settings.ConfigEditor;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.compat.InventoryCompatInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.BlockFace;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every feature has a switch (1.19.0): off stops the feature and answers the one shared message, on behaves as before,
 * an old config.yml gains the new keys, and the placeholders resolve and are empty while their feature is off. Also
 * the contest menu click, which now asks {@code menuHolder} instead of {@code getHolder}.
 */
class SwitchesTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final List<String> PLACEHOLDERS = List.of("photos", "gallery_shown", "gallery_max", "gallery_likes", "collections",
            "contest_running", "contest_theme", "contest_ends_minutes", "contest_entries", "camera_cooldown");
    @TempDir Path dir;

    // ------------------------------------------------------------------ config, descriptions, old configs

    @Test void givenPackagedConfig_whenRead_thenEverySwitchIsAtItsDefaultAndExplained() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        assertEquals(EnumSet.of(Feature.VANILLA_TEXTURES), Feature.offByDefault(), "only the download from Mojang ships off");
        for (Feature feature : Feature.values()) {
            assertTrue(config.isBoolean(feature.path), feature.path);
            assertEquals(feature.defaultOn, config.getBoolean(feature.path), feature.path);
            String parent = feature.path.contains(".") ? feature.path.substring(0, feature.path.lastIndexOf('.')) : feature.path;
            assertFalse(config.getComments(feature.path).isEmpty() && config.getComments(parent).isEmpty(), "comment for " + feature.path);
        }
    }

    @Test void givenEveryLanguage_whenRead_thenTheOffMessageEverySwitchAndPlaceholderHaveTexts() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            assertFalse(lang.getString("feature-off", "").isBlank(), code);
            for (Feature feature : Feature.values()) assertFalse(lang.getString(feature.description(), "").isBlank(), code + " " + feature.description());
            for (String key : PLACEHOLDERS) assertFalse(lang.getString("placeholder." + key, "").isBlank(), code + " placeholder." + key);
        }
        assertEquals("<red>This feature is switched off on this server.", YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/en.yml")).getString("feature-off"));
    }

    @Test void givenAConfigFrom1_18_whenRead_thenEveryFeatureStaysOnAndTheDownloadStaysOff() {
        YamlConfiguration old = YamlConfiguration.loadConfiguration(new File("src/test/resources/config-1.18.yml"));
        assertFalse(old.contains("camera.enabled"));
        assertEquals(Set.of(Feature.VANILLA_TEXTURES), Feature.Switches.read(old).off());
        assertFalse(Feature.Switches.read(old).on(Feature.VANILLA_TEXTURES));
        assertFalse(SnapshotPlugin.enabled(null, Feature.VANILLA_TEXTURES));
        assertTrue(SnapshotPlugin.enabled(null, Feature.CAMERA));
    }

    @Test void givenAConfigFrom1_18_whenCoreMergesDefaults_thenEveryNewSwitchIsAddedAndOldValuesStay() throws Exception {
        Path data = Files.createDirectories(dir.resolve("VystormSnapshot"));
        Files.writeString(data.resolve("config.yml"), Files.readString(Path.of("src/test/resources/config-1.18.yml")).replace("selfie: true", "selfie: false"));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("VystormSnapshot");
        when(plugin.getDataFolder()).thenReturn(data.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getLogger("test"));
        when(plugin.getResource("config.yml")).thenAnswer(call -> Files.newInputStream(Path.of("src/main/resources/config.yml")));
        when(plugin.getConfig()).thenReturn(YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile()));

        assertTrue(ConfigEditor.mergeDefaults(plugin) >= 16);

        YamlConfiguration merged = YamlConfiguration.loadConfiguration(data.resolve("config.yml").toFile());
        for (Feature feature : Feature.values()) assertTrue(merged.isBoolean(feature.path), feature.path);
        assertEquals(Set.of(Feature.SELFIE, Feature.VANILLA_TEXTURES), Feature.Switches.read(merged).off(), "an existing value is never changed");
        assertTrue(Files.readString(data.resolve("config.yml")).contains("The whole plugin"), "comments come along");
        // 1.20.3: both keys of the download from Mojang arrive off, with the EULA comment
        assertFalse(merged.getBoolean("rendering.vanilla-textures.download", true));
        assertFalse(merged.getBoolean("rendering.vanilla-textures.accept-download", true));
        assertTrue(merged.isBoolean("rendering.vanilla-textures.accept-download"));
        assertEquals("auto", merged.getString("rendering.vanilla-textures.version"));
        assertTrue(Files.readString(data.resolve("config.yml")).contains("https://www.minecraft.net/eula"), "the EULA comment comes along");
    }

    // ------------------------------------------------------------------ fixture

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = SnapshotPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Lang lang(SnapshotPlugin plugin) throws Exception {
        Field field = SnapshotPlugin.class.getDeclaredField("lang");
        field.setAccessible(true);
        return (Lang) field.get(plugin);
    }

    private SnapshotPlugin plugin(Feature... off) throws Exception {
        SnapshotPlugin snapshot = mock(SnapshotPlugin.class, CALLS_REAL_METHODS);
        field(snapshot, "cameraKey", NamespacedKey.minecraft("camera"));
        field(snapshot, "lang", mock(Lang.class));
        field(snapshot, "core", mock(API.class, RETURNS_DEEP_STUBS));
        field(snapshot, "switches", Feature.Switches.without(off));
        field(snapshot, "leftClick", true);
        field(snapshot, "lastShot", new HashMap<UUID, Long>());
        field(snapshot, "textureHinted", java.util.concurrent.ConcurrentHashMap.newKeySet());
        doReturn("VystormSnapshot").when(snapshot).getName();
        doReturn(true).when(snapshot).isEnabled();
        doReturn(new YamlConfiguration()).when(snapshot).getConfig();
        return snapshot;
    }

    private static ItemStack camera() {
        ItemStack camera = mock(ItemStack.class);
        ItemMeta meta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(camera.hasItemMeta()).thenReturn(true);
        when(camera.getItemMeta()).thenReturn(meta);
        when(meta.getPersistentDataContainer()).thenReturn(data);
        when(data.has(NamespacedKey.minecraft("camera"), PersistentDataType.BYTE)).thenReturn(true);
        return camera;
    }

    // ------------------------------------------------------------------ camera

    @Test void givenCameraOrCameraMenuOff_whenAPlayerSneakClicks_thenNoMenuOpens() throws Exception {
        Map<String, Feature[]> cases = new LinkedHashMap<>();
        cases.put("on", new Feature[0]);
        cases.put("camera off", new Feature[]{Feature.CAMERA});
        cases.put("menu off", new Feature[]{Feature.CAMERA_MENU});
        for (var entry : cases.entrySet()) {
            SnapshotPlugin snapshot = plugin(entry.getValue());
            doNothing().when(snapshot).openCameraMenu(any());
            Player player = mock(Player.class);
            when(player.isSneaking()).thenReturn(true);
            when(player.hasPermission("vystormsnapshot.use")).thenReturn(true);
            PlayerInteractEvent click = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, camera(), mock(org.bukkit.block.Block.class), BlockFace.UP, EquipmentSlot.HAND);
            snapshot.onUse(click);
            boolean on = entry.getKey().equals("on");
            verify(snapshot, times(on ? 1 : 0)).openCameraMenu(player);
            assertEquals(on, click.isCancelled(), entry.getKey());
        }
    }

    @Test void givenAFeatureOff_whenItsCommandRuns_thenItAnswersTheOffMessage() throws Exception {
        Map<Feature, BiConsumer<SnapshotPlugin, Player>> actions = new LinkedHashMap<>();
        actions.put(Feature.CAPTIONS, (p, player) -> p.caption(player, "hi"));
        actions.put(Feature.BIG_PHOTOS, (p, player) -> p.setFormat(player, new String[]{"format", "2x2"}));
        actions.put(Feature.ALBUM, (p, player) -> p.openAlbum(player, 0));
        actions.put(Feature.SEARCH, (p, player) -> p.openAlbum(player, 0, null, "beach"));
        actions.put(Feature.GALLERY, (p, player) -> p.gallery(player, null));
        actions.put(Feature.POSTCARDS, (p, player) -> p.postcard(player, "Bob", "hi"));
        actions.put(Feature.CONTEST, (p, player) -> p.openContestChest(player));
        actions.put(Feature.ADMIN_PHOTOS, (p, player) -> p.give(player, null));
        actions.put(Feature.CAMERA_MENU, SnapshotPlugin::openCameraMenu);
        for (var entry : actions.entrySet()) {
            SnapshotPlugin snapshot = plugin(entry.getKey());
            Player player = mock(Player.class);
            entry.getValue().accept(snapshot, player);
            verify(lang(snapshot)).send(player, "feature-off");
            verify(player, never()).getInventory();
        }
        SnapshotPlugin admin = plugin(Feature.ADMIN_PHOTOS);
        CommandSender console = mock(CommandSender.class);
        admin.capture(console, new String[]{"capture", "world", "0", "64", "0", "0", "0"});
        verify(lang(admin)).send(console, "feature-off");
    }

    @Test void givenAlbumOff_whenCaptionSearchRuns_thenTheAnswerNamesTheSwitchAndTheCommandStaysListed() throws Exception {
        SnapshotPlugin snapshot = plugin(Feature.ALBUM);
        Player player = mock(Player.class);
        when(lang(snapshot).plain(player, Feature.ALBUM.label())).thenReturn("Album");
        snapshot.openAlbum(player, 0, null, "beach");
        verify(lang(snapshot)).send(player, "feature-needs", "switch", "album.enabled", "name", "Album");
        verify(player, never()).getInventory();
        CommandSpec tree = SnapshotPlugin.commandSpec(snapshot);
        assertTrue(tree.children().get("search").visibleTo(null));
        assertFalse(tree.children().get("album").visibleTo(null));
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn"))
            assertTrue(YamlConfiguration.loadConfiguration(new java.io.File("src/main/resources/lang/" + code + ".yml")).getString("feature-needs", "").matches("(?s).*\\{name}.*\\{switch}.*"), code);
    }

    @Test void givenAllOn_whenACaptionIsSet_thenItLooksAtTheHeldPhoto() throws Exception {
        SnapshotPlugin snapshot = plugin();
        Player player = mock(Player.class, RETURNS_DEEP_STUBS);
        snapshot.caption(player, "hi");
        verify(lang(snapshot)).send(player, "caption-missing");
    }

    @Test void givenSwitchesOff_whenTheCommandTreeIsBuilt_thenTheirSubcommandsAreHidden() throws Exception {
        SnapshotPlugin snapshot = plugin();
        CommandSpec tree = SnapshotPlugin.commandSpec(snapshot);
        assertTrue(tree.children().values().stream().noneMatch(CommandSpec::hidden));
        assertTrue(tree.children().values().stream().allMatch(sub -> sub.visibleTo(null)));
        // the same built tree follows the switch: no second registration needed
        field(snapshot, "switches", Feature.Switches.without(Feature.POSTCARDS, Feature.SEARCH));
        assertFalse(tree.children().get("postcard").visibleTo(null) || tree.children().get("postcards").visibleTo(null));
        assertFalse(tree.children().get("search").visibleTo(null));
        assertTrue(tree.children().get("album").visibleTo(null));
        field(snapshot, "switches", Feature.Switches.without(Feature.CAMERA));
        for (var sub : tree.children().entrySet()) assertEquals(Set.of("reload", "status").contains(sub.getKey()), sub.getValue().visibleTo(null), sub.getKey());
        field(snapshot, "switches", Feature.Switches.without());
        assertTrue(tree.children().values().stream().allMatch(sub -> sub.visibleTo(null)));
    }

    @Test void givenASwitchChanged_whenReloaded_thenTheCommandListIsSentAgainAndNothingIsRegistered() throws Exception {
        SnapshotPlugin snapshot = plugin();
        API core = mock(API.class, RETURNS_DEEP_STUBS);
        var commands = mock(Vystorm.vystorm_core._uiMethods.commands.CommandInterface.class);
        when(core.command()).thenReturn(commands);
        field(snapshot, "core", core);
        field(snapshot, "switches", null);
        field(snapshot, "recipeKey", NamespacedKey.minecraft("camera"));
        YamlConfiguration config = new YamlConfiguration();
        config.set(Feature.TEXTURES.path, false);
        config.set(Feature.CRAFTABLE.path, false);
        doReturn(config).when(snapshot).getConfig();
        doNothing().when(snapshot).reloadConfig();
        doNothing().when(snapshot).saveConfig();
        var load = SnapshotPlugin.class.getDeclaredMethod("load");
        load.setAccessible(true);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            load.invoke(snapshot);
            verify(commands, never()).refresh();
            config.set(Feature.SEARCH.path, false);
            load.invoke(snapshot);
            verify(commands).refresh();
            load.invoke(snapshot);
            verify(commands).refresh();
        }
        verify(commands, never()).register(any(), any(), any());
    }

    @Test void givenTutorialOff_whenThePluginStarts_thenNoTutorialIsRegistered() throws Exception {
        for (boolean enabled : List.of(true, false)) {
            SnapshotPlugin plugin = mock(SnapshotPlugin.class);
            API core = mock(API.class);
            field(plugin, "core", core);
            YamlConfiguration config = new YamlConfiguration();
            config.set("tutorial.enabled", enabled);
            when(plugin.getConfig()).thenReturn(config);
            doCallRealMethod().when(plugin).registerTutorial();
            plugin.registerTutorial();
            verify(core, times(enabled ? 1 : 0)).registerTutorial(plugin);
        }
    }

    // ------------------------------------------------------------------ contest menu holder

    @Test void givenABlockContainer_whenItIsClicked_thenTheContestMenuDoesNotAskForItsHolder() throws Exception {
        SnapshotPlugin snapshot = plugin();
        var listener = new ContestMenu.Clicks(snapshot, InventoryCompatInterface.DEFAULT);
        Inventory chest = mock(Inventory.class);
        when(chest.getLocation()).thenReturn(mock(Location.class));
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(chest);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getView()).thenReturn(view);
        listener.onClick(click);
        verify(chest, never()).getHolder();
        verify(click, never()).setCancelled(true);

        Inventory menu = mock(Inventory.class);
        InventoryHolder holder = mock(InventoryHolder.class);
        when(menu.getHolder()).thenReturn(holder);
        when(view.getTopInventory()).thenReturn(menu);
        listener.onClick(click);
        verify(menu).getHolder();
        verify(click, never()).setCancelled(true); // not one of the contest menus
    }

    // ------------------------------------------------------------------ placeholders

    @Test void givenRegisteredValues_whenResolved_thenTheyAnswerAndAreEmptyWhenTheirFeatureIsOff() throws Exception {
        UUID alex = UUID.randomUUID();
        org.bukkit.OfflinePlayer viewer = PlaceholderTesting.viewer("Alex");
        PhotoContest contest = new PhotoContest(dir.resolve("contest.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        assertTrue(contest.start("Sunset", System.currentTimeMillis(), 3_600_000L));
        Set<Feature> off = EnumSet.noneOf(Feature.class);
        PlaceholderTesting testing = PlaceholderTesting.detached();
        SnapshotPlaceholders.register(testing.placeholders(), PlaceholderTesting.plugin("VystormSnapshot"), new SnapshotPlaceholders.Source() {
            @Override public boolean on(Feature feature) { return !off.contains(feature) && !off.contains(Feature.CAMERA); }
            @Override public int photos(UUID player) { return 7; }
            @Override public Gallery gallery() { return null; }
            @Override public int galleryMax() { return 20; }
            @Override public long collections(UUID player) { return 2; }
            @Override public PhotoContest contest() { return contest; }
            @Override public long cooldownLeft(UUID player, long now) { return 2_500; }
        }, key -> key);
        String text = "{snapshot:photos} {snapshot:collections} {snapshot:contest_running} {snapshot:contest_theme} {snapshot:contest_entries} {snapshot:camera_cooldown}";
        assertEquals("7 2 true Sunset 0 3", testing.resolve(viewer, text));
        off.add(Feature.CONTEST);
        assertEquals("7 2    3", testing.resolve(viewer, text));
        off.add(Feature.CAMERA);
        assertEquals("     ", testing.resolve(viewer, text));
        assertNotNull(alex);
    }
}
