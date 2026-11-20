package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import Vystorm.vystorm_core.lang.Lang;
import Vystorm.vystorm_core.panel.*;
import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static at.esoren.vystormsnapshot.SnapshotPanel.*;

class SnapshotPanelTest {
    static Lang languages() throws Exception {
        Map<String, String> files = new HashMap<>();
        for (String code : List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn"))
            try (var in = SnapshotPanelTest.class.getResourceAsStream("/lang/" + code + ".yml")) {
                files.put(code, new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        return Lang.create("vystormsnapshot", files);
    }

    static PanelContext context(Player player, String language) throws Exception {
        Lang lang = languages();
        PanelContext ctx = mock(PanelContext.class);
        when(ctx.player()).thenReturn(player);
        when(ctx.text(anyString(), any(Object[].class))).thenAnswer(call -> {
            Object[] args = call.getArguments();
            return lang.plain(language, (String) args[0], Arrays.copyOfRange(args, 1, args.length));
        });
        when(ctx.text(anyString())).thenAnswer(call -> lang.plain(language, call.getArgument(0)));
        return ctx;
    }

    @Test void givenSelectedPhoto_whenRendered_thenOnlyItsDetailsAndConfirmedActionsAppear() throws Exception {
        Button print = new Button("print-all", "Print whole photo", Material.PAPER, "2", false, true, false);
        var first = new Row("1", "Castle", "Alex", Material.FILLED_MAP, List.of(new Stat("World", "nether")), List.of());
        var second = new Row("2", "Harbour", "Sam", Material.FILLED_MAP, List.of(new Stat("World", "overworld")), List.of(print));
        View view = new View("Album", "Your photos", Material.BOOK, List.of(first, second), "2", 1, 3, "", List.of(), List.of());
        JsonObject doc = PanelTesting.render(SnapshotPanel.render(context(mock(Player.class), "en"), view, (p, a, v) -> Result.ok()));
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("entries", "details", "print-all", "previous", "next", "search")));
        assertEquals("2", PanelTesting.find(doc, "entries").get("selected").getAsString());
        assertTrue(PanelTesting.find(doc, "details").toString().contains("overworld"));
        assertFalse(PanelTesting.find(doc, "details").toString().contains("nether"));
        assertTrue(PanelTesting.find(doc, "print-all").has("confirm"));
        assertTrue(doc.toString().contains("Page 2/3"));
    }

    @Test void givenCameraSettings_whenRendered_thenTabsContainCurrentChoicesAndNavigation() throws Exception {
        List<Choice> choices = List.of(new Choice("style", "Film style", "sepia", List.of(new Option("color", "Color"), new Option("sepia", "Sepia"))),
                new Choice("timer", "Self-timer", "3", List.of(new Option("0", "Off"), new Option("3", "3 s"))));
        View view = new View("Camera", "Settings", Material.SPYGLASS, List.of(), null, 0, 1, null, choices,
                List.of(new Button("album", "Album", Material.BOOK, "", false, false, true)));
        JsonObject doc = PanelTesting.render(SnapshotPanel.render(context(mock(Player.class), "en"), view, (p, a, v) -> Result.ok()));
        assertTrue(PanelTesting.ids(doc).containsAll(List.of("settings", "style", "timer", "album")));
        assertEquals("sepia", PanelTesting.find(doc, "style").get("value").getAsString());
        assertNull(PanelTesting.find(doc, "entries"));
    }

    @ParameterizedTest @ValueSource(strings = {"en", "de", "fr", "it", "pt_br", "ru", "zh_cn"})
    void givenCameraSettings_whenRendered_thenTheyAreAListWithANameAndADescriptionPerRow(String language) throws Exception {
        var ctx = context(mock(Player.class), language);
        List<Choice> choices = new java.util.ArrayList<>();
        for (String[] c : new String[][]{{"style", "panel.style", "panel.style-desc"}, {"format", "panel.format-name", "panel.format-desc"},
                {"frame", "panel.frame-name", "panel.frame-desc"}, {"timer", "panel.timer", "panel.timer-desc"}})
            choices.add(new Choice(c[0], ctx.text(c[1]), "a", List.of(new Option("a", "A"), new Option("b", "B")), ctx.text(c[2])));
        View view = new View(ctx.text("menu-album"), "", Material.SPYGLASS, List.of(), null, 0, 1, null, choices, List.of());
        JsonObject doc = PanelTesting.render(SnapshotPanel.render(ctx, view, (p, a, v) -> Result.ok()));
        JsonObject settings = PanelTesting.find(doc, "settings");
        assertEquals("list", settings.get("layout").getAsString());
        assertFalse(PanelTesting.plain(settings.get("title")).isBlank());
        for (String id : List.of("style", "format", "frame", "timer")) {
            JsonObject row = PanelTesting.find(doc, id);
            assertFalse(PanelTesting.plain(row.get("label")).isBlank(), id);
            assertFalse(PanelTesting.plain(row.getAsJsonArray("tooltip").get(0)).isBlank(), id);
        }
        assertFalse(doc.toString().contains("panel."), doc.toString());
    }

    @ParameterizedTest @ValueSource(strings = {"en", "de", "fr", "it", "pt_br", "ru", "zh_cn"})
    void givenLanguage_whenEmptyPanelRendered_thenLabelsResolveAndSchemaIsValid(String language) throws Exception {
        var ctx = context(mock(Player.class), language);
        View view = new View(ctx.text("menu-album"), ctx.text("panel.browse"), Material.BOOK, List.of(), null, 0, 1, "", List.of(), List.of());
        var doc = PanelTesting.render(SnapshotPanel.render(ctx, view, (p, a, v) -> Result.ok()));
        assertNotNull(PanelTesting.find(doc, "empty"));
        assertFalse(doc.toString().contains("panel.empty"));
        assertFalse(doc.toString().contains("panel.search"));
        assertNull(PanelTesting.find(doc, "next"));
        assertEquals(languages().plain(language, "panel.search"), PanelTesting.plain(PanelTesting.find(doc, "search").get("label")));
    }
}
