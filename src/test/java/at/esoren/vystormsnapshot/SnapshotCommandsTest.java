package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.commands.CommandRegistration;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.commands.CommandTesting;
import Vystorm.vystorm_core.lang.Lang;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

/** /snapshot through Core's command tree with the real language files: names, routing, completion and help. */
class SnapshotCommandsTest {
    private final List<String> chat = new ArrayList<>();

    private static String read(String name) throws Exception {
        try (InputStream in = SnapshotCommandsTest.class.getResourceAsStream("/lang/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static CommandRegistration registration(SnapshotPlugin handlers) throws Exception {
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugin.getName()).thenReturn("VystormSnapshot");
        Lang lang = Lang.create("VystormSnapshot", Map.of("en", read("en.yml"), "de", read("de.yml"), "zh_cn", read("zh_cn.yml")));
        return CommandTesting.detached(plugin, lang, SnapshotPlugin.commandSpec(handlers));
    }

    private <T extends CommandSender> T capture(T sender) {
        doAnswer(i -> { chat.add(PlainTextComponentSerializer.plainText().serialize((Component) i.getArgument(0))); return null; })
                .when(sender).sendMessage(any(Component.class));
        return sender;
    }

    private Player player(Locale locale, String... permissions) {
        Player p = mock(Player.class);
        when(p.locale()).thenReturn(locale);
        when(p.getUniqueId()).thenReturn(UUID.randomUUID());
        when(p.hasPermission(anyString())).thenAnswer(i -> List.of(permissions).contains((String) i.getArgument(0)));
        return capture(p);
    }

    @Test
    void givenLanguages_whenCompleting_thenSubcommandsInPlayersLanguage() throws Exception {
        CommandRegistration r = registration(null);
        String[] none = {""};
        assertEquals(List.of("albums", "search", "album", "camera", "contest", "gallery", "format", "caption", "help"), r.suggest(player(Locale.US, "vystormsnapshot.use"), "snapshot", none));
        assertEquals(List.of("albums", "suchen", "album", "kamera", "contest", "galerie", "format", "beschriftung", "geben", "aufnehmen", "status", "neuladen", "hilfe"),
                r.suggest(player(Locale.GERMANY, "vystormsnapshot.use", "vystormsnapshot.admin"), "schnappschuss", none));
        assertEquals(List.of("albums", "搜索", "相册", "相机设置", "contest", "画廊", "格式", "说明", "帮助"), r.suggest(player(Locale.SIMPLIFIED_CHINESE, "vystormsnapshot.use"), "相机", none));
        assertEquals(List.of(), r.suggest(player(Locale.US), "snapshot", none), "without vystormsnapshot.use nothing");
    }

    @Test
    void captionSearchRoutesWholeTextAndChecksPermission() throws Exception {
        SnapshotPlugin p = mock(SnapshotPlugin.class);
        CommandRegistration r = registration(p);
        Player german = player(Locale.GERMANY, "vystormsnapshot.use");
        r.execute(german, "snapshot", new String[]{"suchen", "Unser", "Schloss"});
        verify(p).openAlbum(german, 0, null, "Unser Schloss");
        Player chinese = player(Locale.SIMPLIFIED_CHINESE, "vystormsnapshot.use");
        r.execute(chinese, "snapshot", new String[]{"搜索", "城堡"});
        verify(p).openAlbum(chinese, 0, null, "城堡");
        r.execute(player(Locale.US), "snapshot", new String[]{"search", "castle"});
        verifyNoMoreInteractions(p);
    }

    @Test
    void captionsRouteWholeTextAndClearingThroughLocalizedCommands() throws Exception {
        SnapshotPlugin p = mock(SnapshotPlugin.class);
        CommandRegistration r = registration(p);
        Player german = player(Locale.GERMANY, "vystormsnapshot.use");
        r.execute(german, "snapshot", new String[]{"beschriftung", "Unser", "Schloss"});
        verify(p).caption(german, "Unser Schloss");
        r.execute(german, "snapshot", new String[]{"caption"});
        verify(p).caption(german, null);
        Player chinese = player(Locale.SIMPLIFIED_CHINESE, "vystormsnapshot.use");
        r.execute(chinese, "snapshot", new String[]{"说明", "城堡"});
        verify(p).caption(chinese, "城堡");
        assertEquals(List.of(), chat);
        ConsoleCommandSender console = capture(mock(ConsoleCommandSender.class));
        when(console.hasPermission(anyString())).thenReturn(true);
        r.execute(console, "snapshot", new String[]{"caption", "text"});
        assertEquals(List.of("Only players can do that."), chat);
        verifyNoMoreInteractions(p);
    }

    @Test
    void albumsFirstWordCompletesCollectionActions() throws Exception {
        CommandRegistration r = registration(mock(SnapshotPlugin.class));
        Player p = player(Locale.US, "vystormsnapshot.use");
        assertEquals(CollectionsMenu.ACTIONS, r.suggest(p, "snapshot", new String[]{"albums", ""}));
        assertEquals(List.of("share"), r.suggest(p, "snapshot", new String[]{"albums", "S"}));
        assertEquals(List.of("delete"), r.suggest(p, "snapshot", new String[]{"albums", "de"}));
        assertEquals(List.of(), CollectionsMenu.suggestActions("add "));
    }

    @Test
    void albumWorldArgumentRoutesAndCompletesInLocalizedCommands() throws Exception {
        SnapshotPlugin p = mock(SnapshotPlugin.class);
        CommandRegistration r = registration(p);
        Player german = player(Locale.GERMANY, "vystormsnapshot.use");
        when(p.albumWorlds(german)).thenReturn(List.of("archived", "world_nether"));
        assertEquals(List.of("archived", "world_nether"), r.suggest(german, "schnappschuss", new String[]{"album", ""}));
        r.execute(german, "schnappschuss", new String[]{"fotoalbum", "archived"});
        verify(p).openAlbum(german, 0, "archived");
        Player chinese = player(Locale.SIMPLIFIED_CHINESE, "vystormsnapshot.use");
        r.execute(chinese, "snapshot", new String[]{"相册", "world_nether"});
        verify(p).openAlbum(chinese, 0, "world_nether");
        assertEquals(List.of(), chat);
    }

    @Test
    void givenOldEnglishCommands_whenRun_thenSameHandlersAndArguments() throws Exception {
        SnapshotPlugin p = mock(SnapshotPlugin.class);
        CommandRegistration r = registration(p);
        Player player = player(Locale.US, "vystormsnapshot.use", "vystormsnapshot.admin");
        r.execute(player, "snapshot", new String[]{"album"});
        verify(p).openAlbum(player, 0);
        r.execute(player, "snapshot", new String[]{"Camera"});
        verify(p).openCameraMenu(player);
        r.execute(player, "snapshot", new String[]{"gallery"});
        verify(p).gallery(player, null);
        r.execute(player, "snapshot", new String[]{"gallery", "Alex"});
        verify(p).gallery(player, "Alex");
        r.execute(player, "snapshot", new String[]{"format"});
        verify(p).setFormat(eq(player), aryEq(new String[]{"format"}));
        r.execute(player, "snapshot", new String[]{"format", "2x1"});
        verify(p).setFormat(eq(player), aryEq(new String[]{"format", "2x1"}));
        r.execute(player, "snapshot", new String[]{"give"});
        verify(p).give(player, null);
        r.execute(player, "snapshot", new String[]{"give", "Alex"});
        verify(p).give(player, "Alex");
        r.execute(player, "snapshot", new String[]{"capture", "world", "10", "64", "-5.5", "90", "0"});
        verify(p).capture(eq(player), aryEq(new String[]{"capture", "world", "10", "64", "-5.5", "90", "0"}));
        r.execute(player, "snapshot", new String[]{"capture", "world", "10", "64", "-5.5", "90", "0", "2x2"});
        verify(p).capture(eq(player), aryEq(new String[]{"capture", "world", "10", "64", "-5.5", "90", "0", "2x2"}));
        r.execute(player, "snapshot", new String[]{"reload"});
        verify(p).reload(player);
        assertEquals(List.of(), chat, "no error message on any old path");
    }

    @Test
    void givenLocalizedNames_whenRun_thenSameHandlers() throws Exception {
        SnapshotPlugin p = mock(SnapshotPlugin.class);
        CommandRegistration r = registration(p);
        Player german = player(Locale.GERMANY, "vystormsnapshot.use");
        r.execute(german, "schnappschuss", new String[]{"galerie", "Alex"});
        verify(p).gallery(german, "Alex");
        Player chinese = player(Locale.SIMPLIFIED_CHINESE, "vystormsnapshot.use");
        r.execute(chinese, "相机", new String[]{"相册"});
        verify(p).openAlbum(chinese, 0);
        r.execute(chinese, "snapshot", new String[]{"kamera"});
        verify(p).openCameraMenu(chinese);
    }

    @Test
    void givenConsole_whenAdminOrPlayerSub_thenRunOrRefused() throws Exception {
        SnapshotPlugin p = mock(SnapshotPlugin.class);
        ConsoleCommandSender console = capture(mock(ConsoleCommandSender.class));
        when(console.hasPermission(anyString())).thenReturn(true);
        CommandRegistration r = registration(p);
        r.execute(console, "snapshot", new String[]{"give", "Alex"});
        verify(p).give(console, "Alex");
        r.execute(console, "snapshot", new String[]{"capture", "world", "0", "70", "0", "0", "0"});
        verify(p).capture(eq(console), aryEq(new String[]{"capture", "world", "0", "70", "0", "0", "0"}));
        r.execute(console, "snapshot", new String[]{"reload"});
        verify(p).reload(console);
        r.execute(console, "snapshot", new String[]{"status"});
        verify(p).status(console);
        r.execute(console, "snapshot", new String[]{"album"});
        assertEquals(List.of("Only players can do that."), chat);
    }

    @Test
    void givenNoAdmin_whenAdminSub_thenRefused() throws Exception {
        SnapshotPlugin p = mock(SnapshotPlugin.class);
        registration(p).execute(player(Locale.US, "vystormsnapshot.use"), "snapshot", new String[]{"give"});
        verifyNoInteractions(p);
        assertEquals(1, chat.size());
    }

    @Test
    void givenHelp_whenGerman_thenLocalizedUsageAndDescriptions() throws Exception {
        registration(null).execute(player(Locale.GERMANY, "vystormsnapshot.use"), "schnappschuss", new String[]{"hilfe"});
        String all = String.join("\n", chat);
        assertTrue(all.contains("/schnappschuss galerie [SPIELER] – Fotos, die alle ausstellen"), all);
        assertTrue(all.contains("/schnappschuss format [BxH] – Fotoformat"), all);
        assertFalse(all.contains("aufnehmen"), all);
    }

    @Test
    void givenRootAliases_whenRead_thenGermanAndChinese() throws Exception {
        CommandRegistration r = registration(null);
        assertEquals(List.of(), r.fixedAliases());
        assertEquals("schnappschuss", r.lang().raw("de", "commands.snapshot.aliases"));
        assertEquals("相机", r.lang().raw("zh_cn", "commands.snapshot.aliases"));
    }

    @Test
    void givenTree_whenChecked_thenNoNameConflicts() throws Exception {
        assertEquals(List.of(), registration(null).conflicts());
    }

    @Test
    void givenLanguageFiles_whenCompared_thenSameCommandKeysAndEveryNodeDescribed() {
        Set<String> english = commandKeys("en");
        for (String code : List.of("de", "zh_cn")) assertEquals(english, commandKeys(code), code);
        CommandSpec spec = SnapshotPlugin.commandSpec(null);
        for (String code : List.of("en", "de", "zh_cn")) {
            YamlConfiguration yaml = yaml(code);
            List<CommandSpec> nodes = new ArrayList<>(List.of(spec));
            while (!nodes.isEmpty()) {
                CommandSpec node = nodes.removeFirst();
                assertFalse(yaml.getString(node.langPath() + ".description", "").isBlank(), code + " " + node.langPath());
                nodes.addAll(node.children().values());
            }
        }
    }

    private static YamlConfiguration yaml(String code) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(SnapshotCommandsTest.class.getResourceAsStream("/lang/" + code + ".yml"), StandardCharsets.UTF_8));
    }

    private static Set<String> commandKeys(String code) {
        return new TreeSet<>(yaml(code).getConfigurationSection("commands").getKeys(true));
    }
}
