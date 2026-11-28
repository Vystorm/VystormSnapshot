package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.compat.CompatInterface;
import Vystorm.vystorm_core.compat.PlatformCompatInterface;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.*;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.stream.Stream;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 1.22.0: "Send as postcard" and the caption field in the album screen, and the shared postcard code path. */
class AlbumMenuStepTest {
    @TempDir Path folder;
    private SnapshotPlugin plugin;
    private Player owner, reader;
    private Album album;
    private SnapshotPanels screens;

    private Player player() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID()); when(p.isOnline()).thenReturn(true);
        when(p.hasPermission("vystormsnapshot.use")).thenReturn(true);
        when(p.getInventory()).thenReturn(mock(PlayerInventory.class));
        return p;
    }

    @BeforeEach void setup() throws Exception {
        plugin = mock(SnapshotPlugin.class); when(plugin.isEnabled()).thenReturn(true); when(plugin.getName()).thenReturn("VystormSnapshot");
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        owner = player(); reader = player();
        when(plugin.postcardsOn(any())).thenReturn(true); when(plugin.captionsOn()).thenReturn(true); when(plugin.captionMax()).thenReturn(80);
        var files = Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT;
        album = new Album(folder.resolve("album.yml"), Logger.getAnonymousLogger(), files, PlatformCompatInterface.DEFAULT);
        var gallery = new Gallery(folder.resolve("gallery.yml"), Logger.getAnonymousLogger(), files, PlatformCompatInterface.DEFAULT);
        var contest = new PhotoContest(folder.resolve("contest.yml"), files);
        var data = new PhotoCollections(folder.resolve("collections.yml"), Logger.getAnonymousLogger(), files);
        var lang = SnapshotPanelTest.languages();
        Panels panels = mock(Panels.class);
        when(panels.open(any(), anyString())).thenReturn(PanelOpenResult.NATIVE);
        var core = TestCore.api();
        try (var factory = mockStatic(Panels.class)) {
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            screens = new SnapshotPanels(plugin, core, lang, album, gallery, contest, new CollectionsMenu(plugin, core, data, lang));
        }
        album.add(new Album.Entry(7, owner.getUniqueId(), "Alex", "world", "1 2 3", "2026-11-27", "", "Old mill"));
    }

    private com.google.gson.JsonObject album(Player p, SnapshotPanels.State s) throws Exception {
        var ctx = SnapshotPanelTest.context(p, "en");
        return PanelTesting.render(SnapshotPanel.render(ctx, screens.view(ctx, "album", s), screens.actions("album")));
    }

    @Test void givenPostcardsAndCaptionsOn_whenTheAlbumRenders_thenThePhotoHasCaptionGreetingAndPostcardButton() throws Exception {
        var s = new SnapshotPanels.State(); s.greeting = "Hi there";
        var doc = album(owner, s);
        assertNotNull(PanelTesting.find(doc, "postcard"), PanelTesting.ids(doc).toString());
        assertTrue(PanelTesting.find(doc, "caption").toString().contains("Old mill"));
        assertTrue(PanelTesting.find(doc, "greeting").toString().contains("Hi there"), "the greeting stays while the player picks");
        assertFalse(PanelTesting.find(doc, "postcard").has("confirm"));
    }

    @Test void givenPostcardsAndCaptionsOff_whenTheAlbumRenders_thenItIsAsBefore() throws Exception {
        when(plugin.postcardsOn(any())).thenReturn(false); when(plugin.captionsOn()).thenReturn(false);
        var doc = album(owner, new SnapshotPanels.State());
        for (String id : List.of("postcard", "caption", "greeting")) assertNull(PanelTesting.find(doc, id), id);
        assertNotNull(PanelTesting.find(doc, "print"));
    }

    @Test void givenTheGreetingField_whenChanged_thenItIsKeptCleanAndLongTextIsRefused() throws Exception {
        assertTrue(screens.run(owner, "album", "greeting", "  Hello§c from  the mill ").success());
        var ctx = SnapshotPanelTest.context(owner, "en");
        assertTrue(PanelTesting.render(SnapshotPanel.render(ctx, screens.view(ctx, "album", stateOf(owner)), screens.actions("album")))
                .toString().contains("Hello from the mill"));
        assertFalse(screens.run(owner, "album", "greeting", "x".repeat(81)).success());
        when(plugin.postcardsOn(owner)).thenReturn(false);
        assertFalse(screens.run(owner, "album", "greeting", "Hi").success(), "switch and permission are re-checked");
    }

    @Test void givenTheCaptionField_whenChanged_thenTheCommandsCodeSavesIt() {
        when(plugin.caption(owner, 7, "New text")).thenReturn(new SnapshotPlugin.Msg(true, "caption-saved"));
        Result saved = screens.run(owner, "album", "caption:7", "New text");
        assertTrue(saved.success());
        assertTrue(saved.message().contains("Caption saved"), saved.message());
        when(plugin.caption(owner, 7, "x")).thenReturn(new SnapshotPlugin.Msg(false, "caption-not-owner"));
        assertFalse(screens.run(owner, "album", "caption:7", "x").success());
        assertFalse(screens.run(owner, "album", "caption:abc", "x").success());
    }

    @Test void givenNoPicker_whenSendAsPostcardIsClicked_thenTheNameIsAskedAndTheAlbumReopens() {
        when(plugin.playerPicker()).thenReturn(false);
        stateOf(owner).greeting = "Greetings";
        List<Consumer<Player>> next = new ArrayList<>();
        PanelAction a = mock(PanelAction.class);
        when(a.player()).thenReturn(owner);
        doAnswer(call -> { next.add(call.getArgument(0)); return null; }).when(a).closeAndThen(any());
        assertTrue(screens.actions("album").run(a, "postcard", "7").success());
        verify(a, never()).refresh();
        assertEquals(1, next.size());
        next.getFirst().accept(owner);
        verify(plugin).postcardByName(eq(owner), eq(7), eq("Greetings"), any());
    }

    @Test void givenSomeoneElsesPhoto_whenSendAsPostcardIsClicked_thenItIsRefused() {
        PanelAction a = mock(PanelAction.class);
        when(a.player()).thenReturn(reader);
        assertFalse(screens.actions("album").run(a, "postcard", "7").success());
        assertFalse(screens.actions("album").run(a, "postcard", "nope").success());
        verify(a, never()).closeAndThen(any());
        verify(plugin, never()).postcardByName(any(), anyInt(), any(), any());
    }

    private SnapshotPanels.State stateOf(Player p) {
        try {
            var method = SnapshotPanels.class.getDeclaredMethod("state", Player.class, String.class);
            method.setAccessible(true);
            return (SnapshotPanels.State) method.invoke(screens, p, "album");
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    // ---- the shared postcard code (command and menu) ----

    private static void set(Object target, String field, Object value) throws Exception {
        var f = SnapshotPlugin.class.getDeclaredField(field); f.setAccessible(true); f.set(target, value);
    }

    @Test void givenAPhotoOfTheAlbum_whenSentAsPostcard_thenTheCommandsChecksApplyAndTheCardWaitsForAnOfflinePlayer() throws Exception {
        SnapshotPlugin real = mock(SnapshotPlugin.class, CALLS_REAL_METHODS);
        set(real, "album", album);
        set(real, "lang", mock(Lang.class));
        set(real, "postcards", new Postcards(folder.resolve("postcards.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT));
        API core = mock(API.class); CompatInterface compat = mock(CompatInterface.class);
        when(core.compat()).thenReturn(compat); when(compat.platform()).thenReturn(mock(PlatformCompatInterface.class));
        set(real, "core", core);
        doReturn(new YamlConfiguration()).when(real).getConfig();
        doReturn(Logger.getAnonymousLogger()).when(real).getLogger();
        when(owner.getName()).thenReturn("Alex");

        assertEquals("postcard.no-photo", real.postcard(owner, 99, null, "Bob", "").key());
        assertEquals("postcard.unknown", real.postcard(owner, 7, null, "Bob", "").key());
        OfflinePlayer self = mock(OfflinePlayer.class);
        UUID ownerId = owner.getUniqueId();
        when(self.getUniqueId()).thenReturn(ownerId); when(self.hasPlayedBefore()).thenReturn(true);
        assertEquals("postcard.self", real.postcard(owner, 7, self, "Alex", "").key());
        OfflinePlayer bob = mock(OfflinePlayer.class);
        UUID bobId = UUID.randomUUID();
        when(bob.getUniqueId()).thenReturn(bobId); when(bob.hasPlayedBefore()).thenReturn(true); when(bob.getName()).thenReturn("Bob");
        assertEquals("postcard.too-long", real.postcard(owner, 7, bob, "Bob", "x".repeat(81)).key());
        SnapshotPlugin.Msg sent = real.postcard(owner, 7, bob, "Bob", "Hello");
        assertTrue(sent.ok(), sent.key());
        assertEquals("postcard.sent", sent.key());
        assertTrue(Files.readString(folder.resolve("postcards.yml")).contains(bobId.toString()), "the card waits for the offline player");
    }

    /** Load-time trap (LESSONS 2026-11-24): only SnapshotPicks may name the Core 0.71.0 picker types. */
    @Test void givenOlderCore_whenPluginClassesLoad_thenOnlySnapshotPicksNamesThePicker() throws Exception {
        Path dir = Path.of(SnapshotPanels.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve("at/esoren/vystormsnapshot");
        List<Path> classes;
        try (Stream<Path> files = Files.list(dir)) { classes = files.filter(p -> p.toString().endsWith(".class")).toList(); }
        assertTrue(classes.size() > 20, dir.toString());
        for (Path file : classes) {
            String name = file.getFileName().toString();
            if (name.startsWith("SnapshotPicks")) continue;
            String pool;
            try (InputStream in = Files.newInputStream(file)) { pool = new String(in.readAllBytes(), StandardCharsets.ISO_8859_1); }
            for (String newer : List.of("vystorm_core/picker/", "FallbackContext")) assertFalse(pool.contains(newer), name + " names " + newer);
        }
    }

    @Test void givenEveryLanguage_whenRead_thenTheNewTextsExist() throws Exception {
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn")) {
            String text = Files.readString(Path.of("src/main/resources/lang/" + code + ".yml"));
            for (String key : List.of("album-caption-key:", "album-postcard-key:", "  caption:", "  caption-hint:", "  postcard:",
                    "  postcard-text:", "  postcard-text-hint:", "  postcard-pick-title:", "  postcard-pick-subtitle:",
                    "  postcard-player-prompt:", "  postcard-text-prompt:", "  caption-prompt:", "  prompt-failed:"))
                assertTrue(text.contains("\n" + key) || text.contains("\r\n" + key), code + " " + key);
            assertTrue(text.contains("{command}"), code);
        }
    }
}
