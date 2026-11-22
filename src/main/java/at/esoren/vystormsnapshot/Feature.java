package at.esoren.vystormsnapshot;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Every feature of the plugin with its on/off key in config.yml. All are on by default, so a config written before a
 * key existed behaves as before. The one exception is {@link #VANILLA_TEXTURES}: it downloads from Mojang and stays off
 * until the server owner turns it on. The description shown in {@code /vadmin features} is the language key
 * {@code switches.<id>} (see {@link #description()}). Keys that existed before 1.19.0 keep their names.
 */
enum Feature {
    /** Master switch: no photo is taken, no menu opens, commands except reload are off; photos already taken keep showing. */
    CAMERA("camera.enabled"),
    /** Older key that keeps its name; declared as a feature. */
    CRAFTABLE("camera.craftable"),
    FILM("film-cost.enabled"),
    SAVED_PHOTOS("saved-photos.enabled"),
    BIG_PHOTOS("big-photos.enabled"),
    CAMERA_MENU("camera-menu.enabled"),
    /** Older key that keeps its name; declared as a feature. */
    MENU_KEY("camera.menu-key"),
    STYLES("film-styles.enabled"),
    FRAMES("photo-frames.enabled"),
    TIMER("self-timer.enabled"),
    TELEPHOTO("telephoto.enabled"),
    /** Older key that keeps its name; declared as a feature. */
    SELFIE("selfie"),
    FLASH("flash.enabled"),
    /** Older key that keeps its name; declared as a feature. */
    SHOW_LOCATION("show-location"),
    TEXTURES("block-textures.enabled"),
    /** Older key that keeps its name; declared as a feature. */
    SKINS("rendering.player-skins"),
    /** Off by default (1.20.3): fetch the block textures from Mojang; also needs rendering.vanilla-textures.accept-download. */
    VANILLA_TEXTURES("rendering.vanilla-textures.download", false),
    ALBUM("album.enabled"),
    SEARCH("caption-search.enabled"),
    CAPTIONS("captions.enabled"),
    COLLECTIONS("collections.enabled"),
    GALLERY("gallery.enabled"),
    /** Older key that keeps its name; declared as a feature. */
    PRINTS("gallery.prints"),
    POSTCARDS("postcards.enabled"),
    CONTEST("contest.enabled"),
    ADMIN_PHOTOS("admin-photos.enabled"),
    /** Rankings for Vystorm Boards and placeholders (Core 0.67.0 or newer); applies on reload. */
    RANKINGS("rankings.enabled"),
    /** Read once when the plugin starts. */
    TUTORIAL("tutorial.enabled");

    final String path;
    /** The value when config.yml lacks the key. */
    final boolean defaultOn;

    Feature(String path) { this(path, true); }

    Feature(String path, boolean defaultOn) {
        this.path = path;
        this.defaultOn = defaultOn;
    }

    /** The features that are off when config.yml says nothing. */
    static Set<Feature> offByDefault() {
        Set<Feature> off = EnumSet.noneOf(Feature.class);
        for (Feature feature : values()) if (!feature.defaultOn) off.add(feature);
        return off;
    }

    /** Language key of the one-line description. */
    String description() { return "switches." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** Language key of the readable name shown in {@code /vadmin features}. */
    String label() { return "switch-names." + name().toLowerCase(Locale.ROOT).replace('_', '-'); }

    /** Applies only after a server restart. */
    boolean needsRestart() { return this == TUTORIAL; }

    /** The switches as read from a config; a missing key counts as on. */
    static final class Switches {
        private final Set<Feature> off = EnumSet.noneOf(Feature.class);

        static Switches read(ConfigurationSection config) {
            Switches switches = new Switches();
            if (config == null) switches.off.addAll(offByDefault());
            else for (Feature feature : values()) if (!config.getBoolean(feature.path, feature.defaultOn)) switches.off.add(feature);
            return switches;
        }

        /** Every feature at its default, except that the given features are off too. */
        static Switches without(Feature... off) {
            Switches switches = new Switches();
            switches.off.addAll(offByDefault());
            switches.off.addAll(List.of(off));
            return switches;
        }

        /** On when its own switch and the master switch are on. */
        boolean on(Feature feature) { return !off.contains(feature) && (feature == CAMERA || !off.contains(CAMERA)); }

        Set<Feature> off() { return off.isEmpty() ? EnumSet.noneOf(Feature.class) : EnumSet.copyOf(off); }

        @Override public boolean equals(Object other) { return other instanceof Switches s && s.off.equals(off); }

        @Override public int hashCode() { return off.hashCode(); }
    }
}
