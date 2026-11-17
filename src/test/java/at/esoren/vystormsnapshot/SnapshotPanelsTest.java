package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.panel.*;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnapshotPanelsTest {
    @TempDir Path folder;
    private SnapshotPlugin plugin;
    private Player owner, reader;
    private Album album;
    private Gallery gallery;
    private PhotoContest contest;
    private PhotoCollections data;
    private SnapshotPanels screens;
    private Panels panels;
    private final Map<String, PanelSpec> registered = new HashMap<>();

    private Player player() {
        Player p = mock(Player.class);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID()); when(p.isOnline()).thenReturn(true);
        when(p.hasPermission("vystormsnapshot.use")).thenReturn(true);
        when(p.getInventory()).thenReturn(mock(PlayerInventory.class));
        return p;
    }

    @BeforeEach void setup() throws Exception {
        plugin = mock(SnapshotPlugin.class); when(plugin.isEnabled()).thenReturn(true); when(plugin.getName()).thenReturn("VystormSnapshot");
        owner = player(); reader = player();
        when(plugin.galleryOn(any())).thenReturn(true); when(plugin.galleryPrints()).thenReturn(true);
        when(plugin.contestEnabled()).thenReturn(true);
        album = new Album(folder.resolve("album.yml"), Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        gallery = new Gallery(folder.resolve("gallery.yml"), Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT, Vystorm.vystorm_core.compat.PlatformCompatInterface.DEFAULT);
        contest = new PhotoContest(folder.resolve("contest.yml"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        data = new PhotoCollections(folder.resolve("collections.yml"), Logger.getAnonymousLogger(), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT);
        var lang = SnapshotPanelTest.languages();
        panels = mock(Panels.class);
        when(panels.open(any(), anyString())).thenReturn(PanelOpenResult.NATIVE);
        doAnswer(call -> { registered.put(call.getArgument(0), call.getArgument(1)); return null; }).when(panels).register(anyString(), any());
        var core = TestCore.api();
        try (var factory = mockStatic(Panels.class)) {
            factory.when(() -> Panels.of(plugin)).thenReturn(panels);
            screens = new SnapshotPanels(plugin, core, lang, album, gallery, contest, new CollectionsMenu(plugin, core, data, lang));
        }
        screens.register();
    }

    private void photo(int id, Player photographer) {
        album.add(new Album.Entry(id, photographer.getUniqueId(), "Alex", "world", "1 2 3", "2026-11-15", "", "Photo " + id));
        when(plugin.albumEntry(id)).thenAnswer(call -> album.entry(id));
    }

    private com.google.gson.JsonObject document(Player p, String name, SnapshotPanels.State state) throws Exception {
        var ctx = SnapshotPanelTest.context(p, "en");
        return PanelTesting.render(SnapshotPanel.render(ctx, screens.view(ctx, name, state), (who, a, v) -> screens.run(who, name, a, v)));
    }

    @Test void givenEveryMenu_whenRegistered_thenAccessRenderAndChestFallbackExist() {
        assertEquals(new HashSet<>(SnapshotPanels.NAMES), registered.keySet());
        for (var spec : registered.values()) { assertNotNull(spec.render()); assertNotNull(spec.access()); assertNotNull(spec.fallback()); }
        registered.get("album").fallback().accept(owner);
        verify(plugin).openAlbumChest(owner, 0, null, null);
        when(panels.open(owner, "album")).thenReturn(PanelOpenResult.NONE);
        assertFalse(screens.open(owner, "album", 0, null, null, null, null, false));
    }

    @Test void givenUnavailableOrFailingPanels_whenAlbumOpened_thenOriginalFilteredChestIsUsed() throws Exception {
        doCallRealMethod().when(plugin).openPanel(any(), anyString(), anyInt(), any(), any(), any(), any(), anyBoolean());
        doCallRealMethod().when(plugin).openAlbum(any(), anyInt(), any(), any());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        // No registered Panels: the original opener remains usable.
        plugin.openAlbum(owner, 2, "world", "castle");
        verify(plugin).openAlbumChest(owner, 2, "world", "castle");
        var field = SnapshotPlugin.class.getDeclaredField("panelMenus"); field.setAccessible(true); field.set(plugin, screens);
        doThrow(new IllegalStateException("renderer unavailable")).when(panels).open(owner, "album");
        plugin.openAlbum(owner, 3, "end", "tower");
        verify(plugin).openAlbumChest(owner, 3, "end", "tower");
    }

    @Test void givenTwoPlayers_whenPagesChange_thenPagingStaysSeparateAndQuitClearsState() {
        // A forgotten viewer receives the default request again, including default page/filter.
        screens.run(owner, "album", "page", "5"); screens.forget(owner.getUniqueId());
        registered.get("album").fallback().accept(owner);
        verify(plugin).openAlbumChest(owner, 0, null, null);
        screens.run(reader, "album", "page", "3");
        registered.get("album").fallback().accept(reader);
        verify(plugin).openAlbumChest(reader, 3, null, null);
    }

    @Test void givenLargeAlbum_whenPagingAndSearching_thenBoundedRowsAndFiltersArePreserved() throws Exception {
        for (int i = 0; i < 49; i++) photo(i, owner);
        photo(100, reader);
        var s = new SnapshotPanels.State(); s.page = 6;
        var view = screens.view(SnapshotPanelTest.context(owner, "en"), "album", s);
        assertEquals(7, view.pages()); assertEquals(1, view.rows().size()); assertEquals("0", view.rows().getFirst().id());
        assertTrue(PanelTesting.find(document(owner, "album", s), "next").get("disabled").getAsBoolean());
        s.query = "Photo 48"; s.page = 6;
        view = screens.view(SnapshotPanelTest.context(owner, "en"), "album", s);
        assertEquals(0, view.page()); assertEquals(1, view.rows().size()); assertEquals("48", view.rows().getFirst().id());
        assertFalse(screens.run(reader, "album", "print", "48").success());
        verify(plugin, never()).reprint(any(), anyInt(), anyBoolean());
    }

    @Test void givenGallery_whenRendered_thenLikesPrintsAndModerationFollowPermissions() throws Exception {
        photo(1, owner); gallery.publish(1, owner.getUniqueId(), "Alex", "today", 1);
        var s = new SnapshotPanels.State();
        var own = document(owner, "gallery", s);
        assertNotNull(PanelTesting.find(own, "unpublish")); assertNull(PanelTesting.find(own, "like"));
        var other = document(reader, "gallery", s);
        assertNotNull(PanelTesting.find(other, "like")); assertNull(PanelTesting.find(other, "unpublish"));
        assertTrue(PanelTesting.find(other, "print-all").has("confirm"));
        gallery.remove(1);
        assertFalse(screens.run(reader, "gallery", "print-all", "1").success());
        verify(plugin, never()).galleryPrint(any(), anyInt());
    }

    @Test void givenRunningContest_whenRendered_thenVotingAndOwnPhotoPickerAreSeparate() throws Exception {
        photo(1, owner); gallery.publish(1, owner.getUniqueId(), "Alex", "today", 1);
        contest.start("Landscapes", System.currentTimeMillis(), 60000); contest.enter(1, owner.getUniqueId(), "Alex", System.currentTimeMillis());
        var s = new SnapshotPanels.State();
        var doc = document(reader, "contest", s);
        assertTrue(doc.toString().contains("Landscapes")); assertNotNull(PanelTesting.find(doc, "vote"));
        assertNotNull(PanelTesting.find(doc, "enter-picker")); assertNull(PanelTesting.find(document(owner, "contest", s), "vote"));
        assertNotNull(PanelTesting.find(document(owner, "contest-picker", s), "enter"));
        assertNotNull(PanelTesting.find(document(reader, "contest-picker", s), "empty"));
        contest.close();
        assertFalse(screens.run(reader, "contest", "vote", "1").success());
        verify(plugin, never()).contestVote(any(), anyInt(), anyBoolean());
    }

    @Test void givenSharedCollection_whenAccessRevoked_thenStalePrintAndEditAreRejected() throws Exception {
        photo(1, owner); data.create(owner.getUniqueId(), "holiday"); var c = data.own(owner.getUniqueId(), "holiday");
        data.photo(owner.getUniqueId(), c.id(), 1, owner.getUniqueId(), true); data.share(owner.getUniqueId(), c.id(), reader.getUniqueId(), true);
        var s = new SnapshotPanels.State(); s.target = c.id();
        assertNotNull(PanelTesting.find(document(owner, "collections", new SnapshotPanels.State()), "open-collection"));
        var doc = document(reader, "collection", s);
        assertNotNull(PanelTesting.find(doc, "print-all")); assertNull(PanelTesting.find(doc, "delete")); assertNull(PanelTesting.find(doc, "remove-photo"));
        var own = document(owner, "collection", s);
        assertTrue(PanelTesting.find(own, "delete").has("confirm")); assertNotNull(PanelTesting.find(own, "remove-photo"));
        assertTrue(screens.open(reader, "collection", 0, null, null, c.id(), null, false));
        assertFalse(screens.run(reader, "collection", "remove-photo", "1").success());
        assertTrue(screens.run(reader, "collection", "print-all", "1").success());
        verify(plugin).reprint(reader, 1, true); clearInvocations(plugin);
        data.share(owner.getUniqueId(), c.id(), reader.getUniqueId(), false);
        assertFalse(screens.run(reader, "collection", "print-all", "1").success());
        verify(plugin, never()).reprint(any(), anyInt(), anyBoolean());
    }

    @Test void givenSharingMenu_whenRendered_thenReadersCanBeRevokedAndPermissionIsRechecked() throws Exception {
        data.create(owner.getUniqueId(), "holiday"); var c = data.own(owner.getUniqueId(), "holiday");
        data.share(owner.getUniqueId(), c.id(), reader.getUniqueId(), true);
        var s = new SnapshotPanels.State(); s.target = c.id();
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            var offline = mock(org.bukkit.OfflinePlayer.class); when(offline.getName()).thenReturn("Reader");
            bukkit.when(() -> org.bukkit.Bukkit.getOfflinePlayer(reader.getUniqueId())).thenReturn(offline);
            var doc = document(owner, "sharing", s);
            assertTrue(doc.toString().contains("Reader")); assertTrue(PanelTesting.find(doc, "revoke").has("confirm"));
            assertNotNull(PanelTesting.find(doc, "share"));
        }
        screens.open(owner, "sharing", 0, null, null, c.id(), null, false);
        when(owner.hasPermission("vystormsnapshot.use")).thenReturn(false);
        assertFalse(screens.run(owner, "sharing", "revoke", reader.getUniqueId().toString()).success());
        assertTrue(data.get(c.id()).readers().contains(reader.getUniqueId()));
    }

    @Test void givenCamera_whenRendered_thenAllSettingsAppearAndRemovedOptionsAreRejected() throws Exception {
        when(plugin.isCamera(any())).thenReturn(true);
        when(plugin.styles()).thenReturn(List.of(Style.COLOR, Style.SEPIA)); when(plugin.styleOf(any())).thenReturn(Style.COLOR);
        when(plugin.formats()).thenReturn(List.of("1x1", "2x1")); when(plugin.formatOf(any())).thenReturn("1x1");
        when(plugin.frames()).thenReturn(List.of(Style.Frame.NONE)); when(plugin.frameOf(any())).thenReturn(Style.Frame.NONE);
        when(plugin.timers()).thenReturn(List.of(0, 3, 10));
        var doc = document(owner, "camera", new SnapshotPanels.State());
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("settings", "style", "format", "frame", "timer", "album", "gallery")));
        when(plugin.styles()).thenReturn(List.of(Style.COLOR));
        assertFalse(screens.run(owner, "camera", "style", "sepia").success());
        assertTrue(screens.run(owner, "camera", "style", "color").success());
        verify(plugin).cameraSetting(owner, "style:color", false);
        verify(plugin, never()).cameraSetting(owner, "style:sepia", false);
    }
}
