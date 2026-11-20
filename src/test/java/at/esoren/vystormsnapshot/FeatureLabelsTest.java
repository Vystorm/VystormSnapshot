package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface;
import Vystorm.vystorm_core.lang.Lang;
import java.io.File;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.Invocation;

/**
 * {@code /vadmin features} lists every boolean {@code enabled} key and every top-level boolean of config.yml, plus the
 * declared ones. Each of them must have a readable name and a description in all seven languages, so the screen never
 * falls back to a key path such as "Rendering › Player skins". The last test runs the plugin's real declaration code
 * and checks what Core actually receives.
 */
class FeatureLabelsTest {
    private static final List<String> LANGUAGES = List.of("en", "de", "fr", "it", "pt_br", "ru", "zh_cn");
    /** Core's housekeeping names that are never listed as features (ConfigEditorService.NOT_FEATURES). */
    private static final Set<String> NOT_FEATURES = Set.of("metrics", "debug", "verbose", "sound", "console-log", "placeholderapi", "update-check");
    private static final Pattern REF = Pattern.compile("\\{lang:([^:}]+):([^}]+)}");

    private static boolean listedByCore(String path) {
        if (path.equals("enabled") || path.endsWith(".enabled")) return true;
        return path.indexOf('.') < 0 && !NOT_FEATURES.contains(path.toLowerCase(Locale.ROOT));
    }

    @Test void givenEveryBooleanCoreListsAsAFeature_whenChecked_thenItIsADeclaredSwitch() {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(new File("src/main/resources/config.yml"));
        Set<String> declared = new HashSet<>();
        for (Feature feature : Feature.values()) declared.add(feature.path);
        for (String path : config.getKeys(true))
            if (config.isBoolean(path) && listedByCore(path)) assertTrue(declared.contains(path), "feature without a name: " + path);
    }

    @Test void givenEveryLanguage_whenRead_thenEverySwitchHasANameAndADescription() {
        for (String code : LANGUAGES) {
            YamlConfiguration lang = YamlConfiguration.loadConfiguration(new File("src/main/resources/lang/" + code + ".yml"));
            for (Feature feature : Feature.values()) {
                assertFalse(lang.getString(feature.label(), "").isBlank(), code + " " + feature.label());
                assertFalse(lang.getString(feature.description(), "").isBlank(), code + " " + feature.description());
            }
        }
    }

    @Test void givenTheRealDeclarationCode_whenCoreListsTheSwitches_thenEachGotATranslatedNameAndDescription() {
        ConfigEditorInterface config = mock(ConfigEditorInterface.class);
        Lang lang = mock(Lang.class);
        when(lang.ref(anyString())).thenAnswer(call -> "{lang:snapshot:" + call.getArgument(0) + "}");
        Plugin plugin = mock(Plugin.class);
        SnapshotPlugin.declareSwitches(config, plugin, lang);
        assertReceived(config, new File("src/main/resources/config.yml"), "src/main/resources/lang/");
    }

    /**
     * Replays the calls in order with Core 0.66.0 semantics: {@code label(...)} and {@code feature(o, p, l, d)} with
     * any non-null argument set both texts (a null label overwrites an earlier one), {@code feature(o, p)} only
     * declares, {@code notFeature} removes, {@code hidden} hides. Then every switch Core would list must carry
     * {@code {lang:<ns>:<key>}} refs whose keys are non-blank in all seven languages.
     */
    static void assertReceived(ConfigEditorInterface config, File configFile, String langDir) {
        Map<String, String> labels = new HashMap<>(), descriptions = new HashMap<>();
        Map<String, Boolean> declared = new HashMap<>();
        Set<String> hidden = new HashSet<>();
        List<Invocation> calls = new ArrayList<>(mockingDetails(config).getInvocations());
        calls.sort(Comparator.comparingInt(Invocation::getSequenceNumber));
        for (Invocation call : calls) {
            Object[] a = call.getArguments();
            switch (call.getMethod().getName()) {
                case "label" -> { labels.put((String) a[1], (String) a[2]); descriptions.put((String) a[1], (String) a[3]); }
                case "feature" -> {
                    declared.put((String) a[1], true);
                    if (a.length == 4 && (a[2] != null || a[3] != null)) { labels.put((String) a[1], (String) a[2]); descriptions.put((String) a[1], (String) a[3]); }
                }
                case "notFeature" -> declared.put((String) a[1], false);
                case "hidden" -> hidden.add((String) a[1]);
                default -> { }
            }
        }
        Map<String, YamlConfiguration> languages = new LinkedHashMap<>();
        for (String code : LANGUAGES) languages.put(code, YamlConfiguration.loadConfiguration(new File(langDir + code + ".yml")));
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(configFile);
        int listed = 0;
        for (String path : defaults.getKeys(true)) {
            if (!defaults.isBoolean(path) || hidden.contains(path)) continue;
            Boolean feature = declared.get(path);
            if (feature == null ? !listedByCore(path) : !feature) continue;
            listed++;
            translated(languages, path, "name", labels.get(path));
            translated(languages, path, "description", descriptions.get(path));
        }
        assertTrue(listed > 0, "no switch listed");
    }

    private static void translated(Map<String, YamlConfiguration> languages, String path, String what, String ref) {
        assertNotNull(ref, "Core got no " + what + " for " + path + " (it would show the key path)");
        Matcher m = REF.matcher(ref);
        assertTrue(m.matches(), "the " + what + " of " + path + " is no language ref: " + ref);
        for (var language : languages.entrySet())
            assertFalse(language.getValue().getString(m.group(2), "").isBlank(), language.getKey() + " has no " + what + " " + m.group(2) + " for " + path);
    }
}
