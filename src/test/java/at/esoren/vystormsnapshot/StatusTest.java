package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.junit.jupiter.api.Test;

/** {@code /snapshot status} and the one-time admin hint about missing block textures (1.20.3). */
class StatusTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    private static final List<String> KEYS = List.of("title", "textures-off", "textures-missing", "textures-loading", "textures-loaded",
            "textures-failed", "skins-off", "skins-on", "skins-failure", "textures-hint",
            "vanilla-off", "vanilla-consent", "vanilla-own-file", "vanilla-checking", "vanilla-downloading", "vanilla-done", "vanilla-failed", "vanilla-retrying");

    private static void field(Object target, String name, Object value) throws Exception {
        Field field = SnapshotPlugin.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private SnapshotPlugin plugin(SnapshotPlugin.TextureState state, SkinCache skins) throws Exception {
        SnapshotPlugin snapshot = mock(SnapshotPlugin.class, CALLS_REAL_METHODS);
        field(snapshot, "lang", mock(Lang.class));
        field(snapshot, "textureState", state);
        field(snapshot, "textureProblem", "");
        field(snapshot, "texturePack", Path.of("plugins", "VystormSnapshot", "textures.zip"));
        field(snapshot, "textureHinted", java.util.concurrent.ConcurrentHashMap.newKeySet());
        field(snapshot, "blockTextures", BlockTextures.EMPTY);
        field(snapshot, "assets", skins);
        field(snapshot, "playerSkins", skins != null);
        return snapshot;
    }

    private static Lang lang(SnapshotPlugin plugin) throws Exception {
        Field field = SnapshotPlugin.class.getDeclaredField("lang");
        field.setAccessible(true);
        return (Lang) field.get(plugin);
    }

    private static SkinCache failingSkins() {
        PlayerProfile profile = mock(PlayerProfile.class);
        PlayerTextures textures = mock(PlayerTextures.class);
        when(profile.getTextures()).thenReturn(textures);
        try { when(textures.getSkin()).thenReturn(URI.create(SkinCacheTest.MOJANG_URL).toURL()); } catch (Exception e) { throw new IllegalStateException(e); }
        return new SkinCache(p -> profile, n -> CompletableFuture.completedFuture(profile), url -> { throw new SocketTimeoutException("Connect timed out"); },
                Runnable::run, System::currentTimeMillis, w -> {});
    }

    @Test void givenEveryLanguage_thenEveryStatusLineAndTheSubcommandHaveTexts() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            for (String key : KEYS) assertFalse(lang.getString("status." + key, "").isBlank(), code + " status." + key);
            assertFalse(lang.getString("commands.snapshot.sub.status.name", "").isBlank(), code);
            assertTrue(lang.getString("status.textures-missing").contains("{path}"), code);
            assertTrue(lang.getString("status.textures-hint").contains("{path}"), code);
            assertTrue(lang.getString("status.skins-failure").contains("{problem}"), code);
            // 1.20.3 download from Mojang: the exact two keys, the EULA, and every value the line needs
            String consent = lang.getString("status.vanilla-consent");
            assertTrue(consent.contains("rendering.vanilla-textures.download: true") && consent.contains("rendering.vanilla-textures.accept-download: true")
                    && consent.contains("https://www.minecraft.net/eula"), code + " " + consent);
            assertTrue(lang.getString("status.vanilla-off").contains("rendering.vanilla-textures.download: false"), code);
            assertTrue(lang.getString("status.vanilla-own-file").contains("{path}"), code);
            assertTrue(lang.getString("status.vanilla-downloading").matches("(?s).*\\{version}.*") && lang.getString("status.vanilla-downloading").contains("{percent}")
                    && lang.getString("status.vanilla-downloading").contains("{size}"), code);
            assertTrue(lang.getString("status.vanilla-done").contains("{version}") && lang.getString("status.vanilla-done").contains("{date}"), code);
            assertTrue(lang.getString("status.vanilla-failed").contains("{problem}"), code);
            assertTrue(lang.getString("status.vanilla-retrying").contains("{problem}") && lang.getString("status.vanilla-retrying").contains("{time}"), code);
            for (String key : List.of("status.textures-missing", "status.textures-hint"))
                assertTrue(lang.getString(key).contains("rendering.vanilla-textures.accept-download"), code + " " + key + " names the switch");
            assertFalse(lang.getString("switch-names.vanilla-textures", "").isBlank(), code);
        }
    }

    private static VanillaTextures vanilla(VanillaTextures.Status status) throws Exception {
        VanillaTextures textures = new VanillaTextures(Path.of("cache"), Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface.DEFAULT,
                uri -> { throw new AssertionError("no network in this test"); }, Runnable::run, (m, t) -> {}, System::currentTimeMillis, s -> {}, s -> {});
        Field field = VanillaTextures.class.getDeclaredField("status");
        field.setAccessible(true);
        field.set(textures, status);
        return textures;
    }

    @Test void givenEachDownloadState_whenStatus_thenTheMatchingLineIsSent() throws Exception {
        long later = System.currentTimeMillis() + 300_000;
        record Case(VanillaTextures.Status status, String key, List<String> values) {}
        List<Case> cases = List.of(
                new Case(VanillaTextures.Status.OFF, "status.vanilla-off", List.of()),
                new Case(new VanillaTextures.Status(VanillaTextures.State.CONSENT, null, 0, 0, null, 0, null), "status.vanilla-consent", List.of()),
                new Case(new VanillaTextures.Status(VanillaTextures.State.OWN_FILE, null, 0, 0, null, 0, null), "status.vanilla-own-file", List.of("path")),
                new Case(new VanillaTextures.Status(VanillaTextures.State.CHECKING, null, 0, 0, null, 0, null), "status.vanilla-checking", List.of()),
                new Case(new VanillaTextures.Status(VanillaTextures.State.DOWNLOADING, "26.3", 42, 30L << 20, null, 0, null), "status.vanilla-downloading",
                        List.of("version", "26.3", "percent", "42", "size", "30.0 MB")),
                new Case(new VanillaTextures.Status(VanillaTextures.State.DONE, "26.3", 100, 0, null, 0, "2026-11-21T10:00:00Z"), "status.vanilla-done",
                        List.of("version", "26.3", "date", "2026-11-21")),
                new Case(new VanillaTextures.Status(VanillaTextures.State.FAILED, null, 0, 0, "no luck", 0, null), "status.vanilla-failed", List.of("problem", "no luck")),
                new Case(new VanillaTextures.Status(VanillaTextures.State.FAILED, null, 0, 0, "no luck", later, null), "status.vanilla-retrying", List.of("problem", "no luck")));
        for (Case c : cases) {
            SnapshotPlugin snapshot = plugin(SnapshotPlugin.TextureState.MISSING, null);
            field(snapshot, "vanilla", vanilla(c.status()));
            CommandSender admin = mock(CommandSender.class);
            snapshot.status(admin);
            var sent = mockingDetails(lang(snapshot)).getInvocations().stream()
                    .filter(i -> i.getArguments().length > 1 && c.key().equals(i.getArguments()[1])).toList();
            assertEquals(1, sent.size(), c.key());
            List<Object> args = List.of(sent.get(0).getArguments()).subList(2, sent.get(0).getArguments().length);
            if (c.key().equals("status.vanilla-own-file")) assertTrue(String.valueOf(args.get(1)).contains("textures.zip"));
            else if (c.key().equals("status.vanilla-retrying")) assertTrue(args.containsAll(c.values()) && args.contains("time"), args.toString());
            else assertEquals(c.values(), args, c.key());
        }
    }

    @Test void givenTexturesMissingAndASkinFailure_whenStatus_thenThePathAndTheReasonAreShown() throws Exception {
        SkinCache skins = failingSkins();
        Player alex = mock(Player.class);
        when(alex.getUniqueId()).thenReturn(UUID.randomUUID());
        when(alex.getName()).thenReturn("Alex");
        skins.request(alex);
        SnapshotPlugin snapshot = plugin(SnapshotPlugin.TextureState.MISSING, skins);
        CommandSender admin = mock(CommandSender.class);
        snapshot.status(admin);
        Lang lang = lang(snapshot);
        verify(lang).send(admin, "status.title");
        verify(lang).send(eq(admin), eq("status.textures-missing"), eq("path"), contains("textures.zip"));
        verify(lang).send(eq(admin), eq("status.skins-failure"), eq("count"), eq("0"), eq("waiting"), eq("1"), eq("problem"),
                argThat((String s) -> s.contains("Alex") && s.contains("Connect timed out")), eq("ago"), anyString());
    }

    @Test void givenSkinsOffAndTexturesLoaded_whenStatus_thenBothAreSaid() throws Exception {
        SnapshotPlugin snapshot = plugin(SnapshotPlugin.TextureState.LOADED, null);
        CommandSender admin = mock(CommandSender.class);
        snapshot.status(admin);
        verify(lang(snapshot)).send(eq(admin), eq("status.textures-loaded"), eq("count"), eq("0"), eq("path"), anyString());
        verify(lang(snapshot)).send(admin, "status.skins-off");
    }

    @Test void givenTexturesMissing_whenAnAdminTakesPhotos_thenOneHintPerSessionAndNoneForPlayers() throws Exception {
        SnapshotPlugin snapshot = plugin(SnapshotPlugin.TextureState.MISSING, null);
        Method hint = SnapshotPlugin.class.getDeclaredMethod("textureHint", Player.class);
        hint.setAccessible(true);
        Player admin = mock(Player.class), player = mock(Player.class);
        when(admin.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(admin.hasPermission("vystormsnapshot.admin")).thenReturn(true);
        for (int photo = 0; photo < 5; photo++) { hint.invoke(snapshot, admin); hint.invoke(snapshot, player); }
        verify(lang(snapshot), times(1)).send(eq(admin), eq("status.textures-hint"), eq("path"), contains("textures.zip"));
        assertFalse(sentTo(snapshot, player));
        field(snapshot, "textureState", SnapshotPlugin.TextureState.LOADED);
        Player otherAdmin = mock(Player.class);
        when(otherAdmin.getUniqueId()).thenReturn(UUID.randomUUID());
        when(otherAdmin.hasPermission("vystormsnapshot.admin")).thenReturn(true);
        hint.invoke(snapshot, otherAdmin);
        assertFalse(sentTo(snapshot, otherAdmin));
    }

    private static boolean sentTo(SnapshotPlugin snapshot, Object who) throws Exception {
        return mockingDetails(lang(snapshot)).getInvocations().stream().anyMatch(i -> i.getArguments().length > 0 && i.getArguments()[0] == who);
    }
}
