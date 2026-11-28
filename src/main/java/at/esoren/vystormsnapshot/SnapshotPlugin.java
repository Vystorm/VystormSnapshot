package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core.API;
import Vystorm.vystorm_core.CoreApi;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStage;
import Vystorm.vystorm_core._coreMethods.eventRegulator.EventStages;
import Vystorm.vystorm_core.commands.Arg;
import Vystorm.vystorm_core.commands.CommandSpec;
import Vystorm.vystorm_core.lang.Lang;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/**
 * Vystorm Snapshot: a craftable camera that turns what you see into a map. Photos render a few rows per tick, are
 * stored as PNG files and survive restarts; the photo item works in item frames like any map.
 */
public final class SnapshotPlugin extends JavaPlugin implements Listener {
    private Lang lang;
    private SnapshotPanels panelMenus;
    private PhotoStore store;
    private final Palette palette = new Palette();
    private NamespacedKey cameraKey, recipeKey;
    private final List<PhotoJob> jobs = new ArrayList<>();
    private final Map<UUID, Long> lastShot = new HashMap<>();
    private final Map<Integer, Boolean> attached = new HashMap<>();
    private BukkitTask worker;
    private BukkitTask gallerySaver;
    private Material cameraMaterial = Material.SPYGLASS;
    private boolean leftClick = true;
    private Material film = Material.PAPER;
    private double fov = 70, distance = 96;
    private int rowsPerTick = 8, maxJobs = 3;
    private long cooldownMillis = 5000;
    private double zoomFov = 30, flashRange = 16;
    private boolean selfie = true, showLocation = true;
    private List<String> formats = List.of("1x1", "2x1", "2x2", "3x2");
    private NamespacedKey formatKey;
    /** The Core hub; set first thing in onEnable. */
    private API core;
    private Album album;
    private Postcards postcards;
    private CollectionsMenu collections;
    private NamespacedKey styleKey, frameKey, timerKey;
    private List<Integer> timers = List.of(0, 3, 10);
    private final java.util.Set<UUID> timerRunning = new java.util.HashSet<>();
    private List<Style> styles = List.of(Style.values());
    private List<Style.Frame> frames = List.of(Style.Frame.values());
    private boolean menuKey = true;
    private Gallery gallery;
    private PhotoContest contest;
    private boolean galleryEnabled = true, galleryPrints = true;
    private int galleryMax = 20;
    private int captionMax = 80;
    private SkinCache assets;
    /** The opt-in download of the block textures from Mojang (rendering.vanilla-textures). */
    private VanillaTextures vanilla;
    private volatile BlockTextures blockTextures = BlockTextures.EMPTY;
    private volatile long assetGeneration;
    private boolean playerSkins = true;
    /** Every feature switch as read in load(); null (= every feature at its default) until then and in tests that build the plugin by hand. */
    private Feature.Switches switches;

    /** Minimum Vystorm Core: placeholders through api.placeholders(). */
    static final String MIN_CORE = "0.66.0";

    /** Whether a feature (and camera.enabled) is on; static so menus and mocked plugins in tests read the real field. */
    static boolean enabled(SnapshotPlugin plugin, Feature feature) {
        Feature.Switches current = plugin == null ? null : plugin.switches;
        return current == null ? feature.defaultOn : current.on(feature);
    }

    private boolean on(Feature feature) { return enabled(this, feature); }

    /** True (and the shared off message sent) when one of the features is switched off. */
    private boolean off(CommandSender sender, Feature... features) {
        for (Feature feature : features) {
            if (on(feature)) continue;
            lang.send(sender, "feature-off");
            return true;
        }
        return false;
    }

    /** True (and the switch named) when a feature this one depends on is switched off. */
    private boolean needs(CommandSender sender, Feature needed) {
        if (on(needed)) return false;
        lang.send(sender, "feature-needs", "switch", needed.path, "name", lang.plain(sender, needed.label()));
        return true;
    }

    @Override
    public void onEnable() {
        if (!CoreApi.atLeast(MIN_CORE)) {
            getLogger().severe("Vystorm Core " + MIN_CORE + " or newer is required; please update Core first.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        core = CoreApi.api().orElseThrow();

        core.admin().updates().spigot(this, 140051);
        cameraKey = new NamespacedKey(this, "camera");
        recipeKey = new NamespacedKey(this, "camera");
        formatKey = new NamespacedKey(this, "format");
        styleKey = new NamespacedKey(this, "style");
        frameKey = new NamespacedKey(this, "frame");
        timerKey = new NamespacedKey(this, "timer");
        store = new PhotoStore(getDataFolder().toPath().resolve("photos"), core.data().files());
        album = new Album(getDataFolder().toPath().resolve("photos").resolve("album.yml"), getLogger(), core.data().files(), core.compat().platform());
        postcards = new Postcards(getDataFolder().toPath().resolve("postcards.yml"), core.data().files());
        gallery = new Gallery(getDataFolder().toPath().resolve("photos").resolve("gallery.yml"), getLogger(), core.data().files(), core.compat().platform());
        contest = new PhotoContest(getDataFolder().toPath().resolve("photos").resolve("contest.yml"), core.data().files());
        saveDefaultConfig();
        lang = core.lang().register(this);
        registerTutorial();
        collections = new CollectionsMenu(this, core, new PhotoCollections(getDataFolder().toPath().resolve("photos/collections.yml"), getLogger(), core.data().files()), lang);
        assets=new SkinCache(core.compat().platform(), getLogger());
        vanilla = new VanillaTextures(getDataFolder().toPath().resolve("cache"), core.data().files(), getLogger());
        load();
        if(playerSkins) for(Player player:Bukkit.getOnlinePlayers())assets.request(player);
        getServer().getPluginManager().registerEvents(this, this);
        handleSharedEvents(core.priority().stages());
        getServer().getPluginManager().registerEvents(new Album.Clicks(this, core.compat().inventory()), this);
        getServer().getPluginManager().registerEvents(collections, this);
        getServer().getPluginManager().registerEvents(new CameraMenu.Clicks(this, core.compat().inventory()), this);
        getServer().getPluginManager().registerEvents(new Gallery.Clicks(this, core.compat().inventory()), this);
        getServer().getPluginManager().registerEvents(new ContestMenu.Clicks(this, core.compat().inventory()), this);
        Bukkit.getScheduler().runTaskTimer(this, this::contestTick, 1200L, 1200L);
        registerPanels();
        try {
            var gui = core.gui();
            String group = Vystorm.vystorm_core._uiMethods.menu.MenuCategory.COMMUNITY.group();
            gui.registerMenu(this, "album", lang.ref("menu-album"), Material.BOOK, p -> on(Feature.ALBUM) && p.hasPermission("vystormsnapshot.use"),
                    p -> openAlbum(p, 0), group, lang.ref("menu-album-desc"));
            gui.registerMenu(this, "collections", lang.ref("collections.title"), Material.WRITABLE_BOOK, p -> on(Feature.COLLECTIONS) && p.hasPermission("vystormsnapshot.use"),
                    p -> collections.list(p, 0), group, lang.ref("collections.description"));
            gui.registerMenu(this, "gallery", lang.ref("menu-gallery"), Material.PAINTING, this::galleryOn,
                    p -> openGallery(p, 0, false, null, null), group, lang.ref("menu-gallery-desc"));
        } catch (RuntimeException noMainMenu) {
            getLogger().fine("Vystorm Core main menu not available: " + noMainMenu);
        }
        GalleryMenuEntries.register(this, core.gui().playerMenu(), gallery, lang);
        core.settings().config().register(this, "config.yml", this::load);
        declareSwitches();
        try { registerPlaceholders(core.placeholders()); }
        catch (RuntimeException failure) { getLogger().log(Level.WARNING, "Placeholders not registered", failure); }
        rankingsReady = true;
        applyRankings();
        registerCommands();
        worker = core.tasks().of(this, "photo-developing").timer(1L, 1L, this::work);
        gallerySaver = core.tasks().of(this, "gallery-save").timer(200L, 200L, gallery::flush);
        try { for (int id : store.ids()) attach(Bukkit.getMap(id)); }
        catch (IOException failure) { getLogger().log(Level.WARNING, "Could not list stored photos", failure); }
    }

    @Override
    public void onDisable() {
        assetGeneration++;
        if(assets!=null)assets.close();
        if (vanilla != null) vanilla.close();
        if (worker != null) worker.cancel();
        if (gallerySaver != null) gallerySaver.cancel();
        if (gallery != null) gallery.flush();
        jobs.clear();
        if (recipeKey != null) Bukkit.removeRecipe(recipeKey);
    }

    /** The tutorial in /vmenu unless tutorial.enabled is false; read once, because Core lists tutorials when a plugin starts. */
    void registerTutorial() {
        if (!getConfig().getBoolean(Feature.TUTORIAL.path, true)) return;
        try { core.registerTutorial(this); } catch (RuntimeException noTutorial) { getLogger().fine("Tutorial not registered: " + noTutorial); }
    }

    /** Every switch in /vadmin features with its one-line description in the admin's language. */
    private void declareSwitches() { declareSwitches(core.settings().config(), this, lang); }

    /** What Core receives for {@code /vadmin features} (FeatureLabelsTest runs exactly this). */
    static void declareSwitches(Vystorm.vystorm_core._uiMethods.settings.ConfigEditorInterface config, org.bukkit.plugin.Plugin plugin, Lang lang) {
        for (Feature feature : Feature.values()) {
            config.feature(plugin, feature.path, lang.ref(feature.label()), lang.ref(feature.description()));
            if (feature.needsRestart()) config.activation(plugin, feature.path, Vystorm.vystorm_core._uiMethods.settings.SettingPolicy.Activation.RESTART);
        }
    }

    /** Registers /snapshot once; the subcommands of switched-off features stay out of help and tab completion (asked live). */
    private void registerCommands() {
        core.command().register(this, lang, commandSpec(this));
    }

    /** The namespace {@code snapshot}: album, gallery, collections, contest and the camera (server thread; empty while switched off). */
    void registerPlaceholders(Vystorm.vystorm_core._advancedMethods.placeholderHandler.PlaceholderInterface registry) {
        SnapshotPlaceholders.register(registry, this, new SnapshotPlaceholders.Source() {
            @Override public boolean on(Feature feature) { return SnapshotPlugin.this.on(feature); }
            @Override public int photos(UUID player) { return album == null ? 0 : album.of(player, null, null).size(); }
            @Override public Gallery gallery() { return gallery; }
            @Override public int galleryMax() { return galleryMax; }
            @Override public long collections(UUID player) {
                return collections == null ? 0 : collections.data.visible(player).stream().filter(c -> c.owner().equals(player)).count();
            }
            @Override public PhotoContest contest() { return contest; }
            @Override public long cooldownLeft(UUID player, long now) {
                Long last = lastShot.get(player);
                return last == null ? 0 : Math.max(0, cooldownMillis - (now - last));
            }
        }, key -> lang == null ? key : lang.ref(key));
    }

    /** True once onEnable reached the placeholders; a reload before that (the first load) declares nothing yet. */
    private boolean rankingsReady;

    /**
     * {@code snapshot:photos} and {@code snapshot:likes} for Vystorm Boards and placeholders, only on a Core with the
     * capability {@code placeholder-rankings} (0.67.0); on an older Core nothing happens and nothing else changes.
     * Runs at start and after every reload, so {@code rankings.enabled}, {@code album.enabled} and
     * {@code gallery.enabled} apply without a restart.
     */
    private void applyRankings() {
        if (!rankingsReady || core == null) return;
        boolean has;
        try { has = CoreApi.has("placeholder-rankings"); } catch (RuntimeException | LinkageError old) { has = false; }
        SnapshotPlaceholders.rankings(core.placeholders(), this, has, this::on, new SnapshotPlaceholders.RankingData() {
            @Override public java.util.Collection<Album.Entry> albumEntries() { return album == null ? List.of() : album.all(); }
            @Override public java.util.Collection<Gallery.Exhibit> exhibits() { return gallery == null || gallery.broken() != null ? List.of() : gallery.all(); }
        }, key -> lang == null ? key : lang.ref(key), getLogger());
    }

    private void registerPanels() {
        try {
            SnapshotPanels created = new SnapshotPanels(this, core, lang, album, gallery, contest, collections);
            created.register();
            panelMenus = created;
        } catch (RuntimeException unavailable) {
            getLogger().fine("Snapshot panels unavailable: " + unavailable);
        }
    }

    boolean openPanel(Player player, String name, int page, String world, String query, UUID target, String targetName, boolean top) {
        if (panelMenus == null) return false;
        try { return panelMenus.open(player, name, page, world, query, target, targetName, top); }
        catch (RuntimeException unavailable) {
            getLogger().fine("Snapshot panel could not open: " + unavailable);
            return false;
        }
    }

    @EventHandler public void onPanelQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        if (panelMenus != null) panelMenus.forget(event.getPlayer().getUniqueId());
    }

    /**
     * Start and reload read config.yml through here. A file with a YAML error is never saved over: it is reported and
     * left as it is (jar defaults at start, the last good settings on a reload). Returns the problem or null.
     */
    static String readConfig(org.bukkit.plugin.java.JavaPlugin plugin, boolean reload) {
        String problem = YamlCheck.readConfig(plugin, reload);
        if (problem == null && captionDefaults(plugin.getConfig())) plugin.saveConfig();
        return problem;
    }

    /** True once config.yml was applied; a later broken file then keeps these settings. */
    private boolean configLoaded;

    private String load() {
        String problem = readConfig(this, configLoaded);
        if (problem != null && configLoaded) return problem;
        configLoaded = true;
        Feature.Switches before = switches;
        switches = Feature.Switches.read(getConfig());
        loadTextures();
        captionMax = Math.clamp(getConfig().getInt("captions.max-length", 80), 1, 200);
        if (lang != null) lang.reload();
        Material material = core.compat().server().material(getConfig().getString("camera.material", "SPYGLASS"), Material.SPYGLASS);
        cameraMaterial = material == null || !material.isItem() ? Material.SPYGLASS : material;
        leftClick = !"right-click".equalsIgnoreCase(getConfig().getString("camera.trigger", "left-click"));
        String filmName = getConfig().getString("film", "PAPER");
        // film-cost.enabled off: photos are free, whatever film says
        film = !on(Feature.FILM) || "none".equalsIgnoreCase(filmName) ? null : core.compat().server().material(filmName, Material.PAPER);
        if (film != null && !film.isItem()) film = Material.PAPER;
        fov = Math.clamp(getConfig().getDouble("field-of-view", 70), 20, 120);
        distance = Math.clamp(getConfig().getDouble("view-distance", 96), 16, 256);
        rowsPerTick = Math.clamp(getConfig().getInt("rows-per-tick", 8), 1, 128);
        maxJobs = Math.clamp(getConfig().getInt("max-parallel-photos", 3), 1, 32);
        cooldownMillis = Math.max(0, getConfig().getInt("cooldown-seconds", 5)) * 1000L;
        zoomFov = Math.clamp(getConfig().getDouble("zoom-field-of-view", 30), 5, 120);
        flashRange = on(Feature.FLASH) ? Math.clamp(getConfig().getDouble("flash.range", 16), 2, 64) : 0;
        selfie = on(Feature.SELFIE);
        showLocation = on(Feature.SHOW_LOCATION);
        List<String> wanted = new ArrayList<>();
        for (String format : getConfig().getStringList("formats")) if (Format.parse(format) != null) wanted.add(format.toLowerCase(Locale.ROOT));
        formats = wanted.isEmpty() || !on(Feature.BIG_PHOTOS) ? List.of("1x1") : List.copyOf(wanted);
        menuKey = on(Feature.MENU_KEY) && on(Feature.CAMERA_MENU);
        List<Style> wantedStyles = new ArrayList<>();
        for (String name : getConfig().getStringList("styles")) {
            Style style = Style.parse(name);
            if (style != null && !wantedStyles.contains(style)) wantedStyles.add(style);
        }
        styles = wantedStyles.isEmpty() || !on(Feature.STYLES) ? List.of(Style.COLOR) : List.copyOf(wantedStyles);
        List<Style.Frame> wantedFrames = new ArrayList<>();
        for (String name : getConfig().getStringList("frames")) {
            Style.Frame frame = Style.Frame.parse(name);
            if (frame != null && !wantedFrames.contains(frame)) wantedFrames.add(frame);
        }
        frames = wantedFrames.isEmpty() || !on(Feature.FRAMES) ? List.of(Style.Frame.NONE) : List.copyOf(wantedFrames);
        timers = on(Feature.TIMER) ? Format.timers(getConfig().isList("timers") ? getConfig().getIntegerList("timers") : List.of(0, 3, 10)) : List.of(0);
        galleryEnabled = on(Feature.GALLERY);
        galleryPrints = on(Feature.PRINTS);
        galleryMax = Math.max(0, getConfig().getInt("gallery.max-per-player", 20));
        Bukkit.removeRecipe(recipeKey);
        if (on(Feature.CRAFTABLE)) {
            ShapedRecipe recipe = new ShapedRecipe(recipeKey, camera(null));
            recipe.shape("III", "IGI", "IRI");
            recipe.setIngredient('I', Material.IRON_INGOT);
            recipe.setIngredient('G', Material.GLASS_PANE);
            recipe.setIngredient('R', Material.REDSTONE);
            Bukkit.addRecipe(recipe);
            for (Player player : Bukkit.getOnlinePlayers()) player.discoverRecipe(recipeKey);
        }
        // help and tab completion ask the switches live; only send online players their command list again
        if (before != null && !before.equals(switches)) {
            try { core.command().refresh(); }
            catch (RuntimeException failure) { getLogger().log(Level.WARNING, "Command list not sent again", failure); }
        }
        applyRankings();
        return problem;
    }

    /** What {@code /snapshot status} says about the block textures. */
    enum TextureState { OFF, MISSING, LOADING, LOADED, FAILED }

    private volatile TextureState textureState = TextureState.OFF;
    private volatile String textureProblem = "";
    private volatile java.nio.file.Path texturePack;
    /** Admins who already got the missing-textures hint since they joined. */
    private final java.util.Set<UUID> textureHinted = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void loadTextures() {
        playerSkins = on(Feature.SKINS);
        long generation = ++assetGeneration;
        textureHinted.clear();
        if (!on(Feature.TEXTURES)) { // block-textures.enabled off: map colours
            blockTextures = BlockTextures.EMPTY;
            textureState = TextureState.OFF;
            startVanilla(null);
            return;
        }
        var pack = getDataFolder().toPath().resolve(getConfig().getString("rendering.resource-pack", "textures.zip"));
        texturePack = pack;
        boolean fetching = startVanilla(pack);
        if (!java.nio.file.Files.isRegularFile(pack)) {
            blockTextures = BlockTextures.EMPTY;
            textureState = TextureState.MISSING;
            if (fetching) getLogger().info("Camera texture pack missing: " + pack + ". Photos use flat map colours until the download from Mojang"
                    + " (rendering.vanilla-textures) has written it.");
            else getLogger().warning("Camera texture pack missing: " + pack + ". Photos use flat map colours until you put a textures.zip"
                    + " (assets/minecraft/textures/block, models/block, blockstates from a Minecraft client jar) there and run /snapshot reload,"
                    + " or set rendering.vanilla-textures.download and rendering.vanilla-textures.accept-download to true to let the plugin"
                    + " download them from Mojang.");
            return;
        }
        if (textureState != TextureState.LOADED) textureState = TextureState.LOADING;
        boolean queued = assets.background(() -> {
            try {
                BlockTextures loaded = BlockTextures.load(pack);
                if (generation == assetGeneration) {
                    blockTextures = loaded;
                    textureState = loaded.size() > 0 ? TextureState.LOADED : TextureState.FAILED;
                    textureProblem = loaded.size() > 0 ? "" : "no block textures inside (assets/minecraft/textures/block)";
                    if (loaded.size() > 0) getLogger().info("Camera textures loaded: " + loaded.size());
                    else getLogger().warning("Camera texture pack " + pack + " has no block textures in assets/minecraft/textures/block; photos use map colours.");
                }
            } catch (IOException | RuntimeException failure) {
                if (generation == assetGeneration) {
                    textureState = TextureState.FAILED;
                    textureProblem = String.valueOf(failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage());
                }
                getLogger().log(Level.WARNING, "Could not load camera textures; previous textures remain active", failure);
            }
        });
        if (!queued) { textureState = TextureState.FAILED; textureProblem = "the loader is busy, run /snapshot reload again"; }
    }

    /**
     * Starts the opt-in download from Mojang when both keys are true (it checks itself whether textures.zip is the
     * plugin's own and up to date). A finished download loads the textures again on the server thread. {@code pack}
     * null: block textures are off, so nothing is downloaded.
     */
    private boolean startVanilla(java.nio.file.Path pack) {
        if (vanilla == null) return false;
        var config = getConfig();
        var settings = new VanillaTextures.Settings(pack != null && on(Feature.VANILLA_TEXTURES),
                config.getBoolean("rendering.vanilla-textures.accept-download", false),
                config.getString("rendering.vanilla-textures.version", VanillaTextures.AUTO),
                VanillaTextures.minecraftVersion(Bukkit.getBukkitVersion()), pack);
        return vanilla.start(settings, () -> {
            try {
                Bukkit.getScheduler().runTask(this, () -> { if (isEnabled()) loadTextures(); });
            } catch (RuntimeException stopped) { /* plugin disabled meanwhile: the next start loads the zip */ }
        });
    }

    /** The line about the download from Mojang in {@code /snapshot status}. */
    private void vanillaStatus(CommandSender sender, String path) {
        if (vanilla == null) return;
        VanillaTextures.Status state = vanilla.status();
        String version = String.valueOf(state.version());
        switch (state.state()) {
            case OFF -> lang.send(sender, "status.vanilla-off");
            case CONSENT -> lang.send(sender, "status.vanilla-consent");
            case OWN_FILE -> lang.send(sender, "status.vanilla-own-file", "path", path);
            case CHECKING -> lang.send(sender, "status.vanilla-checking");
            case DOWNLOADING -> lang.send(sender, "status.vanilla-downloading", "version", version, "percent", Integer.toString(state.percent()),
                    "size", VanillaTextures.mb(state.size()));
            case DONE -> lang.send(sender, "status.vanilla-done", "version", version,
                    "date", state.date() == null ? "?" : state.date().substring(0, Math.min(10, state.date().length())));
            case FAILED -> {
                if (state.retryAt() > 0) lang.send(sender, "status.vanilla-retrying", "problem", String.valueOf(state.problem()),
                        "time", SkinCache.duration(Math.max(0, state.retryAt() - System.currentTimeMillis())));
                else lang.send(sender, "status.vanilla-failed", "problem", String.valueOf(state.problem()));
            }
        }
    }

    /** {@code /snapshot status}: block textures and player skins as the photos see them now. */
    void status(CommandSender sender) {
        lang.send(sender, "status.title");
        String path = String.valueOf(texturePack == null ? getDataFolder().toPath().resolve("textures.zip") : texturePack);
        switch (textureState) {
            case OFF -> lang.send(sender, "status.textures-off");
            case MISSING -> lang.send(sender, "status.textures-missing", "path", path);
            case LOADING -> lang.send(sender, "status.textures-loading", "path", path);
            case LOADED -> lang.send(sender, "status.textures-loaded", "count", Integer.toString(blockTextures.size()), "path", path);
            case FAILED -> lang.send(sender, "status.textures-failed", "path", path, "problem", textureProblem,
                    "count", Integer.toString(blockTextures.size()));
        }
        vanillaStatus(sender, path);
        if (!playerSkins || assets == null) { lang.send(sender, "status.skins-off"); return; }
        SkinCache.Status skins = assets.status();
        if (skins.lastFailure() == null) lang.send(sender, "status.skins-on", "count", Integer.toString(skins.cached()));
        else lang.send(sender, "status.skins-failure", "count", Integer.toString(skins.cached()), "waiting", Integer.toString(skins.waiting()),
                "problem", skins.lastFailure(), "ago", SkinCache.duration(System.currentTimeMillis() - skins.lastFailureAt()));
    }

    /** Once per session: an admin who takes a photo while the textures are missing is told where the file goes. */
    private void textureHint(Player player) {
        if (textureState != TextureState.MISSING || !player.hasPermission("vystormsnapshot.admin") || !textureHinted.add(player.getUniqueId())) return;
        lang.send(player, "status.textures-hint", "path", String.valueOf(texturePack));
    }
    static boolean captionDefaults(org.bukkit.configuration.file.FileConfiguration config) {
        if (config.contains("captions.max-length", true)) return false;
        config.set("captions.max-length", 80);
        return true;
    }

    /** The answer of a command or menu action: the chat line's key and values; ok when something was saved or sent. */
    record Msg(boolean ok, String key, Object... args) {}

    void send(CommandSender sender, Msg msg) { lang.send(sender, msg.key(), msg.args()); }

    /** Captions belong to the album record, so future prints and gallery icons share the same text. */
    void caption(Player player, String text) {
        if (off(player, Feature.CAPTIONS)) return;
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!(held.getItemMeta() instanceof MapMeta meta) || !meta.hasMapView() || meta.getMapView() == null) {
            lang.send(player, "caption-missing");
            return;
        }
        int map = meta.getMapView().getId();
        Msg result = caption(player, map, text);
        if (result.ok()) {
            core.compat().platform().lore(meta, core.compat().platform().lore(Album.icon(core.compat().platform(), meta.getMapView(), album.entry(map), lang, player)));
            held.setItemMeta(meta);
        }
        send(player, result);
    }

    /** captions.enabled: the caption field of the album menu (1.22.0). */
    boolean captionsOn() { return on(Feature.CAPTIONS); }

    /** {@code /snapshot caption} for a photo of the album menu (1.22.0): same checks and texts, no held print needed. */
    Msg caption(Player player, int map, String text) {
        if (!on(Feature.CAPTIONS)) return new Msg(false, "feature-off");
        Album.Caption result = album.caption(map, player.getUniqueId(), text, captionMax);
        if (result != Album.Caption.SAVED)
            return new Msg(false, "caption-" + result.name().toLowerCase(Locale.ROOT).replace('_', '-'), "max", Integer.toString(captionMax));
        return new Msg(true, album.entry(map).caption().isEmpty() ? "caption-cleared" : "caption-saved");
    }

    int captionMax() { return captionMax; }

    ItemStack camera(Player forLanguage) {
        ItemStack item = new ItemStack(cameraMaterial);
        ItemMeta meta = item.getItemMeta();
        core.compat().platform().displayName(meta, lang.component(forLanguage, "camera-name").decoration(TextDecoration.ITALIC, false));
        meta.getPersistentDataContainer().set(cameraKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        describe(item, forLanguage);
        return item;
    }

    /** Camera lore: how to shoot, the current film style, format and frame, and how to open the camera menu. */
    private void describe(ItemStack camera, Player viewer) {
        List<Component> lore = new ArrayList<>();
        lore.add(lang.component(viewer, leftClick ? "camera-lore-left" : "camera-lore-right"));
        lore.add(lang.component(viewer, "camera-lore-settings", "style", lang.plain(viewer, "style-" + styleOf(camera).id),
                "format", formatOf(camera), "frame", lang.plain(viewer, "frame-" + frameOf(camera).id)));
        int timer = timerOf(camera);
        if (timer > 0) lore.add(lang.component(viewer, "camera-lore-timer", "seconds", Integer.toString(timer)));
        lore.add(lang.component(viewer, "camera-lore-sneak-menu"));
        if (menuKey) lore.add(lang.component(viewer, "camera-lore-menu"));
        core.compat().platform().editMeta(camera, meta -> core.compat().platform().lore(meta, lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList()));
    }

    List<Style> styles() { return styles; }

    List<String> formats() { return formats; }

    List<Style.Frame> frames() { return frames; }

    List<Integer> timers() { return timers; }

    /** Self-timer seconds of a camera; 0 (no timer) when unset or switched off by the admin. */
    int timerOf(ItemStack camera) {
        Integer seconds = camera == null || !camera.hasItemMeta() ? null : camera.getItemMeta().getPersistentDataContainer().get(timerKey, PersistentDataType.INTEGER);
        return seconds != null && timers.contains(seconds) ? seconds : 0;
    }

    /** The film style of a camera; cameras without one (or with a style the admin switched off) use the first allowed. */
    Style styleOf(ItemStack camera) {
        String id = camera == null || !camera.hasItemMeta() ? null : camera.getItemMeta().getPersistentDataContainer().get(styleKey, PersistentDataType.STRING);
        Style style = Style.parse(id == null ? "color" : id);
        return style != null && styles.contains(style) ? style : styles.contains(Style.COLOR) ? Style.COLOR : styles.getFirst();
    }

    Style.Frame frameOf(ItemStack camera) {
        String id = camera == null || !camera.hasItemMeta() ? null : camera.getItemMeta().getPersistentDataContainer().get(frameKey, PersistentDataType.STRING);
        Style.Frame frame = Style.Frame.parse(id == null ? "none" : id);
        return frame != null && frames.contains(frame) ? frame : frames.contains(Style.Frame.NONE) ? Style.Frame.NONE : frames.getFirst();
    }

    void openCameraMenu(Player player) {
        if (off(player, Feature.CAMERA_MENU)) return;
        if (openPanel(player, "camera", 0, null, null, null, null, false)) return;
        openCameraChest(player);
    }

    void openCameraChest(Player player) {
        ItemStack camera = player.getInventory().getItemInMainHand();
        if (!isCamera(camera)) { lang.send(player, "hold-camera"); return; }
        player.openInventory(CameraMenu.open(core.compat().platform(), this, lang, player, camera));
    }

    /** A click in the camera menu: change a setting of the camera in hand, or open the album or the gallery. */
    void cameraSetting(Player player, String setting) { cameraSetting(player, setting, true); }

    void cameraSetting(Player player, String setting, boolean reopen) {
        cameraSetting(player, player.getInventory().getItemInMainHand(), setting, reopen);
    }

    /**
     * {@code camera} is the item to change: the main hand for the chest menu, the slot a Core screen was opened with
     * for the screen (a Core screen hides the hand by selecting an empty slot while it is open).
     */
    void cameraSetting(Player player, ItemStack camera, String setting, boolean reopen) {
        if (setting.equals("album")) { openAlbum(player, 0); return; }
        if (setting.equals("gallery")) { if (galleryOn(player)) openGallery(player, 0, false, null, null); return; }
        if (!isCamera(camera)) { player.closeInventory(); lang.send(player, "hold-camera"); return; }
        String[] parts = setting.split(":", 2);
        if (parts[0].equals("timer")) {
            int seconds;
            try { seconds = Integer.parseInt(parts[1]); } catch (NumberFormatException wrong) { return; }
            if (!timers.contains(seconds)) return;
            core.compat().platform().editMeta(camera, meta -> meta.getPersistentDataContainer().set(timerKey, PersistentDataType.INTEGER, seconds));
            describe(camera, player);
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
            if (reopen) openCameraMenu(player);
            return;
        }
        NamespacedKey key = switch (parts[0]) {
            case "style" -> Style.parse(parts[1]) != null && styles.contains(Style.parse(parts[1])) ? styleKey : null;
            case "frame" -> Style.Frame.parse(parts[1]) != null && frames.contains(Style.Frame.parse(parts[1])) ? frameKey : null;
            case "format" -> formats.contains(parts[1]) ? formatKey : null;
            default -> null;
        };
        if (key == null) return;
        core.compat().platform().editMeta(camera, meta -> meta.getPersistentDataContainer().set(key, PersistentDataType.STRING, parts[1]));
        describe(camera, player);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.4f);
        if (reopen) openCameraMenu(player);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        // swap-hand key (F) with the camera in the main hand opens the camera menu instead of moving it
        if (!menuKey || !isCamera(event.getPlayer().getInventory().getItemInMainHand())) return;
        if (!event.getPlayer().hasPermission("vystormsnapshot.use")) return;
        event.setCancelled(true);
        openCameraMenu(event.getPlayer());
    }

    boolean isCamera(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(cameraKey, PersistentDataType.BYTE);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (on(Feature.CRAFTABLE)) event.getPlayer().discoverRecipe(recipeKey);
        if(playerSkins)assets.request(event.getPlayer());
        Player joined = event.getPlayer();
        if (postcards != null && postcardsEnabled() && postcards.pending(joined.getUniqueId()) > 0)
            Bukkit.getScheduler().runTaskLater(this, () -> { if (joined.isOnline()) collectPostcards(joined, false); }, 60L);
    }

    // ---- postcards ----

    private boolean postcardsEnabled() { return on(Feature.POSTCARDS) && getConfig().getBoolean(Feature.POSTCARDS.path, true); }

    /** {@code /snapshot postcard PLAYER [greeting]}: sends a copy of the held photo. */
    void postcard(Player sender, String to, String greeting) {
        if (!postcardsEnabled()) { lang.send(sender, "feature-off"); return; }
        ItemStack held = sender.getInventory().getItemInMainHand();
        if (!(held.getItemMeta() instanceof MapMeta meta) || !meta.hasMapView() || meta.getMapView() == null || album.entry(meta.getMapView().getId()) == null) {
            lang.send(sender, "postcard.no-photo"); return;
        }
        send(sender, postcard(sender, meta.getMapView().getId(), byName(to), to, greeting));
    }

    /** A player by the name typed (command, the menu's name prompt); null when the server does not know it. */
    org.bukkit.OfflinePlayer byName(String name) {
        // Core's platform part: Bukkit.getOfflinePlayerIfCached is Paper API, Spigot has no such method
        return name == null || name.isBlank() ? null : core.compat().platform().getOfflinePlayerIfCached(name.strip());
    }

    /** A player the menu's player picker chose. */
    org.bukkit.OfflinePlayer byId(UUID id) { return id == null ? null : Bukkit.getOfflinePlayer(id); }

    /** The postcard button of the album menu: postcards on and the command's permission (1.22.0). */
    boolean postcardsOn(Player player) { return postcardsEnabled() && player.hasPermission("vystormsnapshot.postcard"); }

    /**
     * Sends a copy of photo {@code map} as a postcard: the code of {@code /snapshot postcard}, shared by the command
     * (the held photo) and the album menu (1.22.0). {@code to} is the name for the "unknown player" line.
     */
    Msg postcard(Player sender, int map, org.bukkit.OfflinePlayer target, String to, String greeting) {
        if (!postcardsEnabled()) return new Msg(false, "feature-off");
        if (album.entry(map) == null) return new Msg(false, "postcard.no-photo");
        if (target == null || (!target.hasPlayedBefore() && !target.isOnline())) return new Msg(false, "postcard.unknown", "player", to == null ? "?" : to);
        if (target.getUniqueId().equals(sender.getUniqueId())) return new Msg(false, "postcard.self");
        String text = Postcards.clean(greeting);
        if (text == null) return new Msg(false, "postcard.too-long", "max", Postcards.MAX_TEXT);
        long now = System.currentTimeMillis();
        int perDay = Math.clamp(getConfig().getInt("postcards.per-day", 10), 1, 1000);
        if (!sender.hasPermission("vystormsnapshot.admin") && !postcards.mayBeSent(sender.getUniqueId(), now, perDay)) return new Msg(false, "postcard.limit", "max", perDay);
        Postcards.Card card = new Postcards.Card(map, sender.getUniqueId(), sender.getName(), text, now);
        Player online = target.getPlayer();
        if (online != null && online.getInventory().firstEmpty() >= 0) {
            deliver(online, card);
        } else {
            if (!postcards.keep(target.getUniqueId(), card)) return new Msg(false, "postcard.mailbox-full", "player", target.getName());
            if (online != null) lang.send(online, "postcard.waiting", "count", postcards.pending(online.getUniqueId()));
            else try { core.message().inbox().send(this, target.getUniqueId(), "postcard.inbox", "player", sender.getName()); }
            catch (RuntimeException noInbox) { getLogger().fine("Inbox unavailable: " + noInbox); }
        }
        postcards.sentBy(sender.getUniqueId(), now);
        return new Msg(true, "postcard.sent", "player", target.getName() == null ? to : target.getName());
    }

    /** Core's player picker (Core 0.71.0); older Cores ask for the name in a text prompt. */
    boolean playerPicker() { return CoreApi.has("player-picker"); }

    /**
     * "Send as postcard" from the album chest (Q on a photo): player through the picker (or a typed name), then the
     * optional greeting, then {@code back} opens the chest again.
     */
    void postcardFromChest(Player player, int map, Runnable back) {
        Album.Entry photo = album.entry(map);
        if (!postcardsOn(player) || photo == null || !photo.photographer().equals(player.getUniqueId())) { lang.send(player, "denied"); return; }
        player.closeInventory();
        if (playerPicker()) {
            try { SnapshotPicks.postcardFromChest(core, this, lang, player, map, back); return; }
            catch (LinkageError olderCore) { getLogger().fine("Player picker not available: " + olderCore); }
        }
        postcardByName(player, map, null, back);
    }

    /** No picker: the name in a Core text prompt; {@code greeting} null = ask for it afterwards too. */
    void postcardByName(Player player, int map, String greeting, Runnable back) {
        prompt(player, "panel.postcard-player-prompt", "", "/snapshot postcard PLAYER [TEXT]", name -> {
            if (name.isBlank()) { back.run(); return; }
            if (greeting == null) greetingThenSend(player, map, byName(name), name.strip(), back);
            else { send(player, postcard(player, map, byName(name), name.strip(), greeting)); back.run(); }
        }, back);
    }

    /** The optional greeting, then the card goes out and {@code back} runs. */
    void greetingThenSend(Player player, int map, org.bukkit.OfflinePlayer target, String name, Runnable back) {
        prompt(player, "panel.postcard-text-prompt", "", "/snapshot postcard PLAYER [TEXT]", text -> {
            send(player, postcard(player, map, target, name, text));
            back.run();
        }, back);
    }

    /** "Edit caption" from the album chest (Ctrl+Q on a photo): the caption in a text prompt, then the chest again. */
    void captionFromChest(Player player, int map, Runnable back) {
        Album.Entry photo = album.entry(map);
        if (!captionsOn() || photo == null || !photo.photographer().equals(player.getUniqueId())) { lang.send(player, "denied"); return; }
        player.closeInventory();
        prompt(player, "panel.caption-prompt", photo.caption(), "/snapshot caption TEXT", text -> {
            send(player, caption(player, map, text));
            back.run();
        }, back);
    }

    /** A Core text prompt; on Cores that cannot show one the command is named instead. */
    private void prompt(Player player, String questionKey, String initial, String command, java.util.function.Consumer<String> submit, Runnable cancel) {
        try {
            core.dialogs().prompt(this, player, lang.ref("menu-album"), lang.ref(questionKey), initial == null ? "" : initial,
                    text -> { if (player.isOnline() && isEnabled()) submit.accept(text == null ? "" : text); },
                    () -> { if (player.isOnline() && isEnabled()) cancel.run(); }, Player::isOnline);
        } catch (RuntimeException | LinkageError noDialog) {
            getLogger().fine("Text prompt not available: " + noDialog);
            lang.send(player, "panel.prompt-failed", "command", command);
        }
    }

    /** {@code /snapshot postcards}: takes waiting postcards into free inventory slots. */
    void collectPostcards(Player player, boolean manual) {
        if (!postcardsEnabled()) { if (manual) lang.send(player, "feature-off"); return; }
        int waiting = postcards.pending(player.getUniqueId());
        if (waiting == 0) { if (manual) lang.send(player, "postcard.none"); return; }
        int free = 0;
        for (ItemStack slot : player.getInventory().getStorageContents()) if (slot == null || slot.getType().isAir()) free++;
        List<Postcards.Card> cards = postcards.take(player.getUniqueId(), free);
        for (Postcards.Card card : cards) deliver(player, card);
        int left = postcards.pending(player.getUniqueId());
        if (left > 0) lang.send(player, "postcard.inventory-full", "count", left);
    }

    private void deliver(Player player, Postcards.Card card) {
        MapView view = Bukkit.getMap(card.map());
        if (view == null) return;
        ItemStack item = Album.icon(core.compat().platform(), view, album.entry(card.map()), lang, player);
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>(java.util.Optional.ofNullable(core.compat().platform().lore(item)).orElse(List.of()));
        lore.addFirst(lang.component(player, "postcard.lore", "player", card.fromName()).decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, false));
        if (!card.text().isEmpty()) lore.add(1, net.kyori.adventure.text.Component.text("„" + card.text() + "“").decoration(net.kyori.adventure.text.format.TextDecoration.ITALIC, true));
        core.compat().platform().lore(item, lore);
        player.getInventory().addItem(item).values().forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
        lang.send(player, card.text().isEmpty() ? "postcard.received" : "postcard.received-text", "player", card.fromName(), "text", card.text());
        player.playSound(player.getLocation(), "minecraft:item.book.page_turn", 0.8f, 1.1f);
    }

    @EventHandler
    public void onSkinQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        assets.forget(event.getPlayer().getUniqueId());
        textureHinted.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Using the camera goes through Core's stages, so Core decides when it runs among all Vystorm plugins: it is the
     * plugin's own feature (ACT), after every protection. It also takes clicks that arrive cancelled, because a click
     * into the air always does.
     */
    void handleSharedEvents(EventStages stages) {
        stages.handle(this, PlayerInteractEvent.class, EventStage.ACT, true, this::onUse);
    }

    /** Stage ACT, registered in {@link #handleSharedEvents} for cancelled clicks too. */
    public void onUse(PlayerInteractEvent event) {
        if (!on(Feature.CAMERA)) return; // camera.enabled off: the camera is an ordinary item
        if (event.getHand() != EquipmentSlot.HAND || !isCamera(event.getItem())) return;
        Action action = event.getAction();
        boolean left = action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK;
        boolean right = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        if (right && event.getPlayer().isSneaking() && on(Feature.CAMERA_MENU)) {
            event.setCancelled(true);
            if (!event.getPlayer().hasPermission("vystormsnapshot.use")) { lang.send(event.getPlayer(), "denied"); return; }
            openCameraMenu(event.getPlayer());
            return;
        }
        if (leftClick ? !left : !right) return;
        event.setCancelled(true);
        shoot(event.getPlayer());
    }

    private void shoot(Player player) {
        if (!player.hasPermission("vystormsnapshot.use")) { lang.send(player, "denied"); return; }
        long now = System.currentTimeMillis();
        Long last = lastShot.get(player.getUniqueId());
        if (last != null && now - last < cooldownMillis) {
            core.compat().platform().sendActionBar(player, lang.component(player, "cooldown", "seconds", Long.toString((cooldownMillis - (now - last) + 999) / 1000)));
            return;
        }
        if (timerRunning.contains(player.getUniqueId())) { core.compat().platform().sendActionBar(player, lang.component(player, "timer-running")); return; }
        if (jobs.size() >= maxJobs) { core.compat().platform().sendActionBar(player, lang.component(player, "busy")); return; }
        int[] format = Format.parse(formatOf(player.getInventory().getItemInMainHand()));
        if (format == null || !formats.contains(format[0] + "x" + format[1])) format = new int[]{1, 1};
        int cost = format[0] * format[1];
        boolean free = film == null || player.getGameMode() == GameMode.CREATIVE;
        if (!free && !player.getInventory().containsAtLeast(new ItemStack(film), cost)) {
            core.compat().platform().sendActionBar(player, lang.component(player, "no-film", "film", cost + "× " + film.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
            return;
        }
        if (!free) player.getInventory().removeItem(new ItemStack(film, cost));
        lastShot.put(player.getUniqueId(), now);
        int timer = timerOf(player.getInventory().getItemInMainHand());
        if (timer > 0) { startTimer(player, timer, format); return; }
        capture(player, player.getEyeLocation(), player.getInventory().getItemInMainHand(), format, false);
    }

    /**
     * Self-timer: the camera stays where the player clicked, counts down (ticks and action bar) and then takes the
     * photo from there, with the photographer in the picture if they stepped in front of it.
     */
    private void startTimer(Player player, int seconds, int[] format) {
        UUID id = player.getUniqueId();
        Location eye = player.getEyeLocation();
        ItemStack camera = player.getInventory().getItemInMainHand().clone();
        timerRunning.add(id);
        int[] left = {seconds};
        core.compat().platform().sendActionBar(player, lang.component(player, "timer-countdown", "seconds", Integer.toString(left[0])));
        eye.getWorld().playSound(eye, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, 1.6f);
        Bukkit.getScheduler().runTaskTimer(this, task -> {
            left[0]--;
            Player now = Bukkit.getPlayer(id);
            if (now == null || !now.getWorld().equals(eye.getWorld())) { timerRunning.remove(id); task.cancel(); return; }
            if (left[0] > 0) {
                core.compat().platform().sendActionBar(now, lang.component(now, "timer-countdown", "seconds", Integer.toString(left[0])));
                eye.getWorld().playSound(eye, Sound.BLOCK_NOTE_BLOCK_HAT, 0.8f, left[0] <= 2 ? 2.0f : 1.6f);
                return;
            }
            timerRunning.remove(id);
            task.cancel();
            capture(now, eye, camera, format, true);
        }, 20L, 20L);
    }

    /** Takes the photo from {@code eye} with the settings of {@code camera}; {@code timed} keeps the photographer in it. */
    private void capture(Player player, Location eye, ItemStack camera, int[] format, boolean timed) {
        // telephoto while looking through the spyglass
        boolean zoomed = !timed && on(Feature.TELEPHOTO) && player.isHandRaised() && player.getItemInUse() != null && player.getItemInUse().getType() == Material.SPYGLASS;
        double angle = zoomed ? zoomFov : fov;
        // selfie: sneaking turns the camera around, three blocks in front of the face
        boolean turned = !timed && selfie && player.isSneaking();
        var cameraAt = eye.clone();
        if (turned) {
            cameraAt = Format.selfie(eye);
        }
        boolean dark = eye.getBlock().getLightLevel() < 8;
        double flashUsed = dark ? flashRange : 0;
        eye.getWorld().playSound(eye, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.8f, 1.8f);
        eye.getWorld().playSound(eye, Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.6f, 1.4f);
        var flash = eye.clone().add(eye.getDirection().multiply(0.6));
        try { eye.getWorld().spawnParticle(Particle.FLASH, flash, 1, 0, 0, 0, 0, org.bukkit.Color.WHITE); }
        catch (IllegalArgumentException noColour) { eye.getWorld().spawnParticle(Particle.FLASH, flash, 1, 0, 0, 0, 0); }
        UUID photographer = player.getUniqueId();
        String name = player.getName();
        String world = eye.getWorld().getName();
        String where = showLocation ? eye.getBlockX() + " " + eye.getBlockY() + " " + eye.getBlockZ() + " · " + Format.facing(eye.getYaw()) : "";
        int tilesX = format[0], tilesY = format[1];
        jobs.add(new PhotoJob(cameraAt, turned || timed ? null : player, player, angle, distance, tilesX, tilesY, flashUsed, styleOf(camera), frameOf(camera),
                blockTextures,playerSkins?assets:null,
                pixels -> deliver(photographer, name, world, where, pixels, tilesX, tilesY)));
        core.compat().platform().sendActionBar(player, lang.component(player, "developing"));
        textureHint(player);
    }

    String formatOf(ItemStack camera) {
        if (camera == null || !camera.hasItemMeta()) return "1x1";
        return camera.getItemMeta().getPersistentDataContainer().getOrDefault(formatKey, PersistentDataType.STRING, "1x1");
    }

    /** {@code /snapshot format <WxH>} on the camera in hand. */
    void setFormat(Player player, String[] args) {
        if (off(player, Feature.BIG_PHOTOS)) return;
        ItemStack camera = player.getInventory().getItemInMainHand();
        if (!isCamera(camera)) { lang.send(player, "hold-camera"); return; }
        if (args.length < 2 || !formats.contains(args[1].toLowerCase(Locale.ROOT))) {
            lang.send(player, "usage-format", "formats", String.join(", ", formats));
            return;
        }
        String chosen = args[1].toLowerCase(Locale.ROOT);
        core.compat().platform().editMeta(camera, meta -> meta.getPersistentDataContainer().set(formatKey, PersistentDataType.STRING, chosen));
        describe(camera, player);
        int[] size = Format.parse(chosen);
        lang.send(player, "format-set", "format", chosen, "maps", Integer.toString(size[0] * size[1]));
    }

    void openAlbum(Player player, int page) { openAlbum(player, page, null); }

    void openAlbum(Player player, int page, String world) {
        openAlbum(player, page, world, null);
    }

    void openAlbum(Player player, int page, String world, String query) {
        if (query != null ? off(player, Feature.SEARCH) || needs(player, Feature.ALBUM) : off(player, Feature.ALBUM)) return;
        if (openPanel(player, "album", page, world, query, null, null, false)) return;
        openAlbumChest(player, page, world, query);
    }

    void openAlbumChest(Player player, int page, String world, String query) {
        player.openInventory(album.menu(lang, player, page, galleryOn(player) ? gallery : null, world, query, postcardsOn(player), captionsOn()));
    }

    List<String> albumWorlds(Player player) { return album.worlds(player.getUniqueId()); }

    // --- gallery ---

    boolean galleryOn(Player player) { return galleryEnabled && gallery.broken() == null && player.hasPermission("vystormsnapshot.gallery"); }

    boolean galleryPrints() { return galleryPrints; }

    Album.Entry albumEntry(int map) { return album.entry(map); }

    List<Integer> photoSet(int map) { return album.set(map); }

    void openGallery(Player player, int page, boolean top, UUID onlyBy, String onlyName) {
        if (openPanel(player, "gallery", page, null, null, onlyBy, onlyName, top)) return;
        openGalleryChest(player, page, top, onlyBy, onlyName);
    }

    void openGalleryChest(Player player, int page, boolean top, UUID onlyBy, String onlyName) {
        if (galleryEnabled && gallery.broken() != null && player.hasPermission("vystormsnapshot.gallery")) {
            lang.send(player, player.hasPermission("vystormsnapshot.gallery.moderate") ? "gallery-broken-admin" : "gallery-broken", "file", gallery.broken());
            return;
        }
        if (!galleryEnabled) { lang.send(player, "feature-off"); return; }
        if (!galleryOn(player)) { lang.send(player, "denied"); return; }
        player.openInventory(gallery.menu(this, lang, player, page, top, onlyBy, onlyName));
    }

    /** Right-click in the album: put a photo (a big photo as a whole) on show, or take it down again. */
    void togglePublish(Player player, int map) {
        int first = album.set(map).getFirst();
        Album.Entry entry = album.entry(first);
        if (entry == null || !entry.photographer().equals(player.getUniqueId())) return;
        if (gallery.remove(first)) { core.compat().platform().sendActionBar(player, lang.component(player, "gallery-taken-down")); return; }
        if (galleryMax > 0 && gallery.count(player.getUniqueId()) >= galleryMax) {
            core.compat().platform().sendActionBar(player, lang.component(player, "gallery-full", "max", Integer.toString(galleryMax)));
            return;
        }
        gallery.publish(first, player.getUniqueId(), player.getName(), LocalDate.now().toString(), System.currentTimeMillis());
        core.compat().platform().sendActionBar(player, lang.component(player, "gallery-published"));
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_FRAME_ADD_ITEM, 0.8f, 1.2f);
    }

    /** Shift-right-click in the gallery: the photographer or a moderator takes a photo down. */
    void unpublish(Player player, int map) {
        Gallery.Exhibit exhibit = gallery.exhibit(map);
        if (exhibit == null) return;
        if (!exhibit.photographer.equals(player.getUniqueId()) && !player.hasPermission("vystormsnapshot.gallery.moderate")) return;
        gallery.remove(map);
        core.compat().platform().sendActionBar(player, lang.component(player, "gallery-taken-down"));
    }

    // ---- photo contest ----

    boolean contestEnabled() { return on(Feature.CONTEST) && getConfig().getBoolean(Feature.CONTEST.path, true); }

    /** "2 h 15 min", "45 min". */
    static String duration(long minutes) {
        long h = minutes / 60, m = minutes % 60;
        return h > 0 ? h + " h" + (m > 0 ? " " + m + " min" : "") : m + " min";
    }

    void openContest(Player player) {
        if (openPanel(player, "contest", 0, null, null, null, null, false)) return;
        openContestChest(player);
    }

    void openContestChest(Player player) {
        if (!contestEnabled()) { lang.send(player, "feature-off"); return; }
        long now = System.currentTimeMillis();
        if (!contest.running(now)) {
            var last = contest.last();
            if (last.isPresent() && last.get().map() >= 0) lang.send(player, "contest.none-last", "theme", last.get().theme(), "player", last.get().name(), "votes", last.get().votes());
            else lang.send(player, "contest.none");
            return;
        }
        player.openInventory(ContestMenu.entries(core.compat().platform(), this, lang, player, contest));
    }

    void openContestPicker(Player player) {
        if (openPanel(player, "contest-picker", 0, null, null, null, null, false)) return;
        openContestPickerChest(player);
    }

    void openContestPickerChest(Player player) {
        if (!contest.running(System.currentTimeMillis())) { openContest(player); return; }
        List<Integer> own = new ArrayList<>();
        for (Gallery.Exhibit e : gallery.order(false, player.getUniqueId())) own.add(e.map);
        if (own.isEmpty()) { player.closeInventory(); lang.send(player, "contest.no-photos"); return; }
        player.openInventory(ContestMenu.picker(core.compat().platform(), this, lang, player, own));
    }

    void contestEnter(Player player, int map) { contestEnter(player, map, true); }

    String contestEnter(Player player, int map, boolean reopen) {
        Gallery.Exhibit exhibit = gallery.exhibit(map);
        if (exhibit == null || !exhibit.photographer.equals(player.getUniqueId())) return "taken";
        String result = contest.enter(map, player.getUniqueId(), player.getName(), System.currentTimeMillis());
        lang.send(player, "contest.enter-" + result);
        if (reopen) openContest(player);
        return result;
    }

    void contestVote(Player player, int map) { contestVote(player, map, true); }

    String contestVote(Player player, int map, boolean reopen) {
        String result = contest.vote(map, player.getUniqueId(), System.currentTimeMillis());
        if (result.equals("voted")) player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        else core.compat().platform().sendActionBar(player, lang.component(player, "contest.vote-" + result));
        if (reopen) openContest(player);
        return result;
    }

    /** {@code /snapshot contest start <hours> <theme>} and {@code /snapshot contest end}. */
    void contestAdmin(CommandSender sender, String action, String hours, String theme) {
        if (!contestEnabled()) { lang.send(sender, "feature-off"); return; }
        if ("end".equals(action)) {
            if (contest.theme() == null) { lang.send(sender, "contest.none"); return; }
            PhotoContest.Result result = finishContest();
            if (!(sender instanceof Player) && result != null) lang.send(sender, result.map() < 0 ? "contest.ended-empty" : "contest.winner",
                    "theme", result.theme(), "player", result.name(), "votes", result.votes());
            return;
        }
        double h;
        try { h = Double.parseDouble(hours == null ? "" : hours.replace(',', '.')); } catch (NumberFormatException bad) { h = -1; }
        String clean = theme == null ? "" : theme.replace('§', '&').strip();
        if (h < 0.25 || h > 24 * 14 || clean.isEmpty() || clean.length() > 48) { lang.send(sender, "contest.start-usage"); return; }
        if (!contest.start(clean, System.currentTimeMillis(), (long) (h * 3_600_000L))) { lang.send(sender, "contest.already"); return; }
        for (Player online : Bukkit.getOnlinePlayers()) if (online.hasPermission("vystormsnapshot.use"))
            lang.send(online, "contest.started", "theme", clean, "time", duration(Math.round(h * 60)));
        if (!(sender instanceof Player)) lang.send(sender, "contest.started", "theme", clean, "time", duration(Math.round(h * 60)));
    }

    private void contestTick() { if (contest != null && contestEnabled() && contest.due(System.currentTimeMillis())) finishContest(); }

    /** Closes the contest, announces the winner (chat, Core news) and pays the reward. */
    private PhotoContest.Result finishContest() {
        PhotoContest.Result result = contest.close();
        if (result == null) return null;
        if (result.map() < 0) { for (Player online : Bukkit.getOnlinePlayers()) lang.send(online, "contest.ended-empty", "theme", result.theme()); return result; }
        for (Player online : Bukkit.getOnlinePlayers()) lang.send(online, "contest.winner", "theme", result.theme(), "player", result.name(), "votes", result.votes());
        try {
            core.message().news().post(this, lang.plain(Bukkit.getConsoleSender(), "contest.news-title", "theme", result.theme()),
                    lang.plain(Bukkit.getConsoleSender(), "contest.news-text", "player", result.name(), "votes", result.votes()));
        } catch (RuntimeException noNews) { getLogger().fine("Contest news not posted: " + noNews); }
        Player winner = Bukkit.getPlayer(result.photographer());
        int xp = Math.clamp(getConfig().getInt("contest.reward-xp", 100), 0, 100_000);
        if (winner != null && xp > 0) winner.giveExp(xp);
        for (String command : getConfig().getStringList("contest.reward-commands")) {
            String line = command.replace("{player}", result.name()).strip();
            if (line.startsWith("/")) line = line.substring(1);
            if (!line.isEmpty()) getServer().dispatchCommand(getServer().getConsoleSender(), line);
        }
        if (winner == null) core.message().inbox().send(this, result.photographer(), "contest.won-inbox", "theme", result.theme(), "votes", result.votes());
        return result;
    }

    void like(Player player, int map) {
        Gallery.Like result = gallery.like(map, player.getUniqueId());
        if (result == Gallery.Like.LIKED) {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.6f);
            Player photographer = Bukkit.getPlayer(gallery.exhibit(map).photographer);
            if (photographer != null) core.compat().platform().sendActionBar(photographer, lang.component(photographer, "gallery-liked-by", "player", player.getName()));
        } else if (result == Gallery.Like.OWN) {
            core.compat().platform().sendActionBar(player, lang.component(player, "gallery-own"));
        }
    }

    /** Shift-click in the gallery: a print of the whole photo, one film per map like a reprint. */
    void galleryPrint(Player player, int map) {
        if (galleryPrints && gallery.shown(map)) reprint(player, map, true);
    }

    /** Reprint from the album: one film per map, free in creative. {@code whole}: every map of a big photo. */
    void reprint(Player player, int mapId, boolean whole) {
        List<ItemStack> copies = new ArrayList<>();
        for (int id : whole ? album.set(mapId) : List.of(mapId)) {
            MapView view = Bukkit.getMap(id);
            if (view != null) copies.add(Album.icon(core.compat().platform(), view, album.entry(id), lang, player));
        }
        if (copies.isEmpty()) return;
        int cost = copies.size();
        boolean free = film == null || player.getGameMode() == GameMode.CREATIVE;
        if (!free && !player.getInventory().containsAtLeast(new ItemStack(film), cost)) {
            core.compat().platform().sendActionBar(player, lang.component(player, "no-film", "film", (cost == 1 ? "" : cost + "× ") + film.name().toLowerCase(Locale.ROOT).replace('_', ' ')));
            return;
        }
        if (!free) player.getInventory().removeItem(new ItemStack(film, cost));
        for (ItemStack rest : player.getInventory().addItem(copies.toArray(ItemStack[]::new)).values()) player.getWorld().dropItemNaturally(player.getLocation(), rest);
        player.playSound(player.getLocation(), Sound.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 0.8f, 1.2f);
    }

    private void work() {
        if (jobs.isEmpty()) return;
        int budget = rowsPerTick;
        Iterator<PhotoJob> iterator = jobs.iterator();
        while (iterator.hasNext() && budget > 0) {
            int rows = Math.max(1, budget / Math.max(1, jobs.size()));
            if (iterator.next().step(rows, palette)) iterator.remove();
            budget -= rows;
        }
    }

    private void deliver(UUID photographer, String name, String worldName, String where, byte[] pixels, int tilesX, int tilesY) {
        Player player = Bukkit.getPlayer(photographer);
        var world = player != null ? player.getWorld() : Bukkit.getWorld(worldName);
        if (world == null) world = Bukkit.getWorlds().getFirst();
        List<ItemStack> photos = new ArrayList<>();
        String date = LocalDate.now().toString();
        for (int ty = 0; ty < tilesY; ty++) for (int tx = 0; tx < tilesX; tx++) {
            MapView view = develop(world, Format.tile(pixels, tilesX * Optics.SIZE, tx, ty));
            String part = tilesX * tilesY == 1 ? "" : (ty * tilesX + tx + 1) + "/" + (tilesX * tilesY);
            Album.Entry entry = new Album.Entry(view.getId(), photographer, name, worldName, where, date, part);
            album.add(entry);
            photos.add(Album.icon(core.compat().platform(), view, entry, lang, player));
        }
        if (player == null) return;
        for (ItemStack rest : player.getInventory().addItem(photos.toArray(ItemStack[]::new)).values()) player.getWorld().dropItemNaturally(player.getLocation(), rest);
        core.compat().platform().sendActionBar(player, lang.component(player, tilesX * tilesY == 1 ? "done" : "done-big", "maps", Integer.toString(tilesX * tilesY), "format", tilesX + "x" + tilesY));
        player.playSound(player.getLocation(), Sound.ITEM_BOOK_PAGE_TURN, 0.8f, 1.2f);
    }

    /** New locked map showing the photo; the PNG is written next to the other photos. */
    private MapView develop(org.bukkit.World world, byte[] pixels) {
        MapView view = Bukkit.createMap(world);
        byte[] copy = pixels.clone();
        // saved-photos.enabled off: the photo is not written to disk and shows only until the next restart
        if (on(Feature.SAVED_PHOTOS)) try {
            store.save(view.getId(), copy, index -> Palette.color((byte) index).getRGB() & 0xFFFFFF);
        } catch (IOException failure) {
            getLogger().log(Level.WARNING, "Could not save photo " + view.getId(), failure);
        }
        prepare(view, copy);
        return view;
    }

    /** Console/admin photo from any position, e.g. for spawn boards: {@code capture <world> <x> <y> <z> <yaw> <pitch>}. */
    void capture(CommandSender sender, String[] args) {
        if (off(sender, Feature.ADMIN_PHOTOS)) return;
        if (args.length < 7) { lang.send(sender, "usage-capture"); return; }
        if(jobs.size()>=maxJobs){lang.send(sender,"busy");return;}
        org.bukkit.World world = Bukkit.getWorld(args[1]);
        if (world == null) { lang.send(sender, "unknown-world", "world", args[1]); return; }
        try {
            org.bukkit.Location eye = new org.bukkit.Location(world, Double.parseDouble(args[2]), Double.parseDouble(args[3]), Double.parseDouble(args[4]),
                    Float.parseFloat(args[5]), Float.parseFloat(args[6]));
            if(!Double.isFinite(eye.getX())||!Double.isFinite(eye.getY())||!Double.isFinite(eye.getZ())||!Float.isFinite(eye.getYaw())||!Float.isFinite(eye.getPitch()))
                throw new NumberFormatException("Non-finite camera position");
            if(!world.isChunkLoaded(eye.getBlockX()>>4,eye.getBlockZ()>>4)){lang.send(sender,"capture-unloaded");return;}
            int[] size = args.length > 7 && Format.parse(args[7]) != null ? Format.parse(args[7]) : new int[]{1, 1};
            jobs.add(new PhotoJob(eye, null, fov, distance, size[0], size[1], 0, Style.COLOR,Style.Frame.NONE,blockTextures,playerSkins?assets:null,pixels -> {
                List<String> ids = new ArrayList<>();
                for (int ty = 0; ty < size[1]; ty++) for (int tx = 0; tx < size[0]; tx++)
                    ids.add(Integer.toString(develop(world, Format.tile(pixels, size[0] * Optics.SIZE, tx, ty)).getId()));
                lang.send(sender, "captured", "id", ids.getFirst(),"ids",String.join(", ",ids));
            }));
        } catch (NumberFormatException wrong) {
            lang.send(sender, "usage-capture");
        }
    }

    private void prepare(MapView view, byte[] pixels) { prepare(view, new PhotoRenderer(pixels)); }

    private void prepare(MapView view, PhotoRenderer photo) {
        for (var renderer : new ArrayList<>(view.getRenderers())) view.removeRenderer(renderer);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        view.addRenderer(photo);
        attached.put(view.getId(), true);
    }

    /** A stored photo gets its renderer now; its PNG is read when a player first sees the map. */
    private void attach(MapView view) {
        if (view == null || attached.containsKey(view.getId())) return;
        int id = view.getId();
        if (!java.nio.file.Files.isRegularFile(store.file(id))) return;
        prepare(view, new PhotoRenderer(store.later(id, rgb -> palette.match(rgb),
                failure -> getLogger().log(Level.WARNING, "Could not load photo " + id, failure))));
    }

    @EventHandler
    public void onMapLoad(MapInitializeEvent event) { attach(event.getMap()); }

    /**
     * {@code /snapshot} as a Vystorm Core command tree: names, aliases, usage and help come from
     * {@code commands.snapshot.*} in the language files ({@code /schnappschuss galerie}, {@code /相机 相册}); English
     * always works. {@code p} may be {@code null} in tests that only inspect names and help.
     */
    static CommandSpec commandSpec(SnapshotPlugin p) {
        return CommandSpec.command("snapshot")
                .permission("vystormsnapshot.use")
                .executes(ctx -> { if (ctx.sender() instanceof org.bukkit.entity.Player player) p.openAlbum(player, 0); else ctx.sender().sendMessage("/snapshot help"); })
                .sub("albums", s -> gate(s, p, Feature.COLLECTIONS).playerOnly().arg(Arg.greedy("action").optional().suggests((ctx, prefix) -> CollectionsMenu.suggestActions(prefix)))
                        .executes(ctx -> { if (!p.off(ctx.player(), Feature.COLLECTIONS)) p.collections.command(ctx.player(), ctx.get("action")); }))
                // listed while caption search is on, so that with the album off it can say which switch it needs
                .sub("search", s -> gate(s, p, Feature.SEARCH).playerOnly().arg(Arg.greedy("text"))
                        .executes(ctx -> p.openAlbum(ctx.player(), 0, null, ctx.get("text"))))
                .sub("album", s -> gate(s, p, Feature.ALBUM).playerOnly().arg(Arg.word("world").optional().suggests((ctx, prefix) -> p.albumWorlds(ctx.player())))
                        .executes(ctx -> {
                            String world = ctx.get("world");
                            if (world == null) p.openAlbum(ctx.player(), 0);
                            else p.openAlbum(ctx.player(), 0, world);
                        }))
                .sub("camera", s -> gate(s, p, Feature.CAMERA_MENU).playerOnly().executes(ctx -> p.openCameraMenu(ctx.player())))
                .sub("contest", s -> gate(s, p, Feature.CONTEST).playerOnly().executes(ctx -> p.openContest(ctx.player()))
                        .sub("start", c -> c.permission("vystormsnapshot.admin").arg(Arg.word("hours")).arg(Arg.greedy("theme"))
                                .executes(ctx -> p.contestAdmin(ctx.sender(), "start", ctx.get("hours"), ctx.get("theme"))))
                        .sub("end", c -> c.permission("vystormsnapshot.admin").executes(ctx -> p.contestAdmin(ctx.sender(), "end", null, null))))
                .sub("gallery", s -> gate(s, p, Feature.GALLERY).playerOnly().arg(Arg.playerName("player").optional())
                        .executes(ctx -> p.gallery(ctx.player(), ctx.get("player"))))
                .sub("format", s -> gate(s, p, Feature.BIG_PHOTOS).playerOnly().arg(Arg.word("format").optional().suggests((ctx, prefix) -> p.formats()))
                        .executes(ctx -> {
                            String format = ctx.get("format");
                            p.setFormat(ctx.player(), format == null ? new String[]{"format"} : new String[]{"format", format});
                        }))
                .sub("postcard", s -> gate(s, p, Feature.POSTCARDS).permission("vystormsnapshot.postcard").playerOnly().arg(Arg.playerName("player")).arg(Arg.greedy("text").optional())
                        .executes(ctx -> p.postcard(ctx.player(), ctx.get("player"), ctx.get("text"))))
                .sub("postcards", s -> gate(s, p, Feature.POSTCARDS).permission("vystormsnapshot.postcard").playerOnly().executes(ctx -> p.collectPostcards(ctx.player(), true)))
                .sub("caption", s -> gate(s, p, Feature.CAPTIONS).playerOnly().arg(Arg.greedy("text").optional())
                        .executes(ctx -> p.caption(ctx.player(), ctx.get("text"))))
                .sub("give", s -> gate(s, p, Feature.ADMIN_PHOTOS).permission("vystormsnapshot.admin").arg(Arg.playerName("player").optional())
                        .executes(ctx -> p.give(ctx.sender(), ctx.get("player"))))
                .sub("capture", s -> gate(s, p, Feature.ADMIN_PHOTOS).permission("vystormsnapshot.admin")
                        .arg(Arg.word("world").suggests((ctx, prefix) -> p.worldNames()))
                        .arg(Arg.word("x")).arg(Arg.word("y")).arg(Arg.word("z")).arg(Arg.word("yaw")).arg(Arg.word("pitch"))
                        .arg(Arg.word("format").optional().suggests((ctx, prefix) -> p.formats()))
                        .executes(ctx -> {
                            List<String> args = new ArrayList<>(List.of("capture", ctx.get("world"), ctx.get("x"), ctx.get("y"), ctx.get("z"), ctx.get("yaw"), ctx.get("pitch")));
                            if (ctx.get("format") != null) args.add(ctx.get("format"));
                            p.capture(ctx.sender(), args.toArray(String[]::new));
                        }))
                .sub("status", s -> s.permission("vystormsnapshot.admin").executes(ctx -> p.status(ctx.sender())))
                .sub("reload", s -> s.permission("vystormsnapshot.admin").executes(ctx -> p.reload(ctx.sender())))
                .build();
    }

    /**
     * Keeps a subcommand out of help and tab completion while its switch (or camera.enabled) is off, asked at every
     * completion so a switch applies at once; it still answers the off message.
     */
    static CommandSpec.Builder gate(CommandSpec.Builder sub, SnapshotPlugin p, Feature feature) {
        return p == null ? sub : sub.visibleWhen(() -> enabled(p, feature));
    }

    void reload(CommandSender sender) {
        String problem = load();
        // skins that failed are asked again at once, cached ones fetched again (a player may have changed theirs)
        if (playerSkins && assets != null) assets.refresh(Bukkit.getOnlinePlayers());
        if (problem != null) lang.send(sender, "config-error", "problem", problem);
        else lang.send(sender, "reloaded");
    }

    /** {@code /snapshot gallery [player]}: everyone's photos, or one photographer's. */
    void gallery(Player player, String name) {
        if (off(player, Feature.GALLERY)) return;
        if (name == null) { openGallery(player, 0, false, null, null); return; }
        var target = core.compat().platform().getOfflinePlayerIfCached(name);
        if (target == null) { lang.send(player, "unknown-player"); return; }
        openGallery(player, 0, false, target.getUniqueId(), target.getName() == null ? name : target.getName());
    }

    /** {@code /snapshot give [player]}: a camera for the player (default: the sender). */
    void give(CommandSender sender, String name) {
        if (off(sender, Feature.ADMIN_PHOTOS)) return;
        Player target = name != null ? Bukkit.getPlayerExact(name) : sender instanceof Player self ? self : null;
        if (target == null) { lang.send(sender, "unknown-player"); return; }
        target.getInventory().addItem(camera(target));
        lang.send(sender, "given", "player", target.getName());
    }

    List<String> worldNames() {
        List<String> out = new ArrayList<>();
        for (org.bukkit.World world : Bukkit.getWorlds()) out.add(world.getName());
        return out;
    }
}
