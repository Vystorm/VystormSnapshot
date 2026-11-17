package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStreamReader;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class LangTest {
    private static YamlConfiguration load(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(LangTest.class.getResourceAsStream("/" + name), StandardCharsets.UTF_8));
    }

    @Test
    void englishAndGermanHaveTheSameKeys() {
        assertEquals(load("lang/en.yml").getKeys(true), load("lang/de.yml").getKeys(true));
    }

    @Test
    void chineseHasTheSameKeysAndPlaceholders() {
        YamlConfiguration en = load("lang/en.yml");
        YamlConfiguration zh = load("lang/zh_cn.yml");
        assertEquals(en.getKeys(true), zh.getKeys(true));
        for (String key : en.getKeys(true)) {
            if (en.isConfigurationSection(key)) continue;
            assertEquals(placeholders(en, key), placeholders(zh, key), key);
        }
    }

    private static TreeSet<String> placeholders(YamlConfiguration yaml, String key) {
        String text = yaml.isList(key) ? String.join("\n", yaml.getStringList(key)) : yaml.getString(key);
        TreeSet<String> found = new TreeSet<>();
        Matcher matcher = Pattern.compile("\\{[^}]+}").matcher(text);
        while (matcher.find()) found.add(matcher.group());
        return found;
    }

    @Test
    void everyStyleAndFrameHasAName() {
        YamlConfiguration en = load("lang/en.yml");
        for (Style style : Style.values()) assertTrue(en.contains("style-" + style.id), style.id);
        for (Style.Frame frame : Style.Frame.values()) assertTrue(en.contains("frame-" + frame.id), frame.id);
    }

    @Test
    void usageLinesHaveNoRawArgumentTags() {
        for (String file : new String[]{"lang/en.yml", "lang/de.yml", "lang/zh_cn.yml"}) {
            YamlConfiguration yaml = load(file);
            for (String key : yaml.getKeys(false)) if (key.startsWith("usage")) assertFalse(yaml.getString(key).contains("<arg"), file + " " + key);
        }
    }

    @Test
    void defaultConfigListsEveryStyleAndFrame() {
        YamlConfiguration config = load("config.yml");
        assertEquals(Style.values().length, config.getStringList("styles").stream().map(Style::parse).distinct().filter(s -> s != null).count());
        assertEquals(Style.Frame.values().length, config.getStringList("frames").stream().map(Style.Frame::parse).distinct().filter(f -> f != null).count());
        assertTrue(config.getBoolean("gallery.enabled"));
    }
}
