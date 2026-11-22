package at.esoren.vystormsnapshot;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

/**
 * Bounded, asynchronous official skin lookup. Missing skins keep the colour fallback. A failed lookup is logged once
 * per cause (at most once per {@link #WARN_INTERVAL_MILLIS}) with its reason and tried again later with a growing
 * wait; {@link #refresh} (after {@code /snapshot reload}) asks again at once.
 */
final class SkinCache implements AutoCloseable {
    /** Why a skin is missing; each cause warns on its own. */
    enum Cause { NETWORK, HTTP, IMAGE, URL, NO_SKIN, LOOKUP, BUSY }

    static final class Failure extends IOException {
        final Cause cause;
        Failure(Cause cause, String message) { super(message); this.cause = cause; }
    }

    interface Fetcher { SurfaceTexture fetch(URL url) throws IOException; }

    /** For {@code /snapshot status}: skins in memory, players waiting for another try, the last failure (or null). */
    record Status(int cached, int waiting, String lastFailure, long lastFailureAt) {}

    /** Waits after the 1st, 2nd, ... failure of one player; the last one repeats. */
    static final long[] BACKOFF_MILLIS = {30_000, 120_000, 600_000, 1_800_000, 3_600_000};
    static final long WARN_INTERVAL_MILLIS = 3_600_000;
    private static final int MAX_PLAYERS = 256;

    /** One player's lookup: running, or failed {@code failures} times and due again at {@code retryAt}. */
    private record Attempt(boolean running, int failures, long retryAt) {}

    private final Map<UUID, PlayerFigure.Skin> skins = new ConcurrentHashMap<>();
    private final Map<UUID, Attempt> attempts = new ConcurrentHashMap<>();
    private final Map<Cause, Long> warned = new ConcurrentHashMap<>();
    private final Function<Player, PlayerProfile> profiles;
    private final Function<String, ? extends CompletableFuture<? extends PlayerProfile>> lookup;
    private final Fetcher fetcher;
    private final Executor executor;
    private final LongSupplier clock;
    private final Consumer<String> warn;
    private volatile boolean closed;
    private volatile String lastFailure;
    private volatile long lastFailureAt;

    SkinCache(Vystorm.vystorm_core.compat.PlatformCompatInterface platform, java.util.logging.Logger logger) {
        this(platform::getPlayerProfile, name -> Bukkit.createPlayerProfile(name).update(), SkinCache::fetch,
                new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> {
                    Thread t = new Thread(r, "snapshot-assets");
                    t.setDaemon(true);
                    return t;
                }), System::currentTimeMillis, logger::warning);
    }

    SkinCache(Function<Player, PlayerProfile> profiles, Function<String, ? extends CompletableFuture<? extends PlayerProfile>> lookup, Fetcher fetcher,
              Executor executor, LongSupplier clock, Consumer<String> warn) {
        this.profiles = profiles;
        this.lookup = lookup;
        this.fetcher = fetcher;
        this.executor = executor;
        this.clock = clock;
        this.warn = warn;
    }

    PlayerFigure.Skin get(UUID id) {
        return skins.get(id);
    }

    void forget(UUID id) {
        skins.remove(id);
        attempts.remove(id);
    }

    Status status() {
        int waiting = 0;
        for (Attempt attempt : attempts.values()) if (!attempt.running() && attempt.failures() > 0) waiting++;
        return new Status(skins.size(), waiting, lastFailure, lastFailureAt);
    }

    /** Main thread, for every photo and join: starts a lookup only when none is running and no wait is pending. */
    void request(Player player) { request(player, false); }

    /** After {@code /snapshot reload}: forgets the waits and asks again for everyone, cached skins included. */
    void refresh(Collection<? extends Player> online) {
        if (closed) return;
        attempts.entrySet().removeIf(entry -> !entry.getValue().running());
        warned.clear();
        lastFailure = null;
        lastFailureAt = 0;
        for (Player player : online) request(player, true);
    }

    private void request(Player player, boolean force) {
        if (closed) return;
        UUID id = player.getUniqueId();
        long now = clock.getAsLong();
        boolean[] start = {false};
        attempts.compute(id, (key, attempt) -> {
            if (attempt == null) {
                if (!force && skins.containsKey(id)) return null;
                if (attempts.size() >= MAX_PLAYERS) return null;
                start[0] = true;
                return new Attempt(true, 0, 0);
            }
            if (attempt.running() || (!force && now < attempt.retryAt())) return attempt;
            start[0] = true;
            return new Attempt(true, attempt.failures(), 0);
        });
        if (!start[0]) return;
        String name = player.getName();
        PlayerProfile profile;
        try {
            profile = profiles.apply(player);
        } catch (RuntimeException failure) {
            failed(id, name, Cause.LOOKUP, "the server did not hand out the player's profile (" + failure + ")");
            return;
        }
        if (profile != null && profile.getTextures().getSkin() != null) {
            download(id, name, profile);
            return;
        }
        // No skin in the online profile (offline-mode servers): ask Mojang by name.
        CompletableFuture<? extends PlayerProfile> asked;
        try {
            asked = lookup.apply(name);
        } catch (RuntimeException failure) {
            failed(id, name, Cause.LOOKUP, "the name lookup failed (" + failure + ")");
            return;
        }
        asked.orTimeout(10, TimeUnit.SECONDS).whenComplete((updated, failure) -> {
            if (failure != null) failed(id, name, Cause.LOOKUP, "the name lookup at Mojang failed (" + unwrap(failure) + ")");
            else if (updated == null || updated.getTextures().getSkin() == null)
                failed(id, name, Cause.NO_SKIN, "the player's profile has no skin (on an offline-mode server players have no official skin)");
            else download(id, name, updated);
        });
    }

    private static Throwable unwrap(Throwable failure) {
        return failure instanceof CompletionException && failure.getCause() != null ? failure.getCause() : failure;
    }

    private void download(UUID id, String name, PlayerProfile profile) {
        var textures = profile.getTextures();
        URL url = textures.getSkin();
        if (url == null || closed) return;
        boolean slim = textures.getSkinModel() == PlayerTextures.SkinModel.SLIM;
        boolean queued = background(() -> {
            try {
                SurfaceTexture image = fetcher.fetch(url);
                if (closed) return;
                // only while the player is still wanted: forget() on quit removes the running attempt
                if (attempts.remove(id) == null) return;
                skins.put(id, new PlayerFigure.Skin(image, slim));
            } catch (Failure failure) {
                failed(id, name, failure.cause, failure.getMessage());
            } catch (IOException failure) {
                failed(id, name, Cause.NETWORK, "textures.minecraft.net could not be reached (" + failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : ": " + failure.getMessage()) + ")");
            } catch (RuntimeException failure) {
                failed(id, name, Cause.IMAGE, "the skin image could not be read (" + failure + ")");
            }
        });
        if (!queued) failed(id, name, Cause.BUSY, "the download queue is full");
    }

    private void failed(UUID id, String name, Cause cause, String reason) {
        if (closed) return;
        long now = clock.getAsLong();
        Attempt after = attempts.computeIfPresent(id, (key, attempt) -> {
            int failures = attempt.failures() + 1;
            return new Attempt(false, failures, now + BACKOFF_MILLIS[Math.min(failures, BACKOFF_MILLIS.length) - 1]);
        });
        lastFailure = name + ": " + reason;
        lastFailureAt = now;
        Long last = warned.get(cause);
        if (last != null && now - last < WARN_INTERVAL_MILLIS) return;
        warned.put(cause, now);
        long wait = after == null ? BACKOFF_MILLIS[0] : after.retryAt() - now;
        warn.accept("Player skin of " + name + " not loaded: " + reason + ". Photos show a plain figure until it works; trying again in "
                + duration(wait) + ". /snapshot status shows the state. Further failures of this kind stay quiet for an hour.");
    }

    static String duration(long millis) {
        long seconds = Math.max(1, millis / 1000);
        if (seconds < 120) return seconds + " s";
        if (seconds < 7200) return seconds / 60 + " min";
        return seconds / 3600 + " h";
    }

    /** Runs work on the loader thread; false when the bounded queue is full or the cache is closed. */
    boolean background(Runnable action) {
        if (closed) return false;
        try {
            executor.execute(action);
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }

    static URI skinUri(URL url) throws IOException {
        if (!Set.of("http", "https").contains(url.getProtocol()) || !"textures.minecraft.net".equalsIgnoreCase(url.getHost())
                || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null
                || !url.getPath().matches("/texture/[0-9a-fA-F]{32,128}"))
            throw new Failure(Cause.URL, "the profile's skin address is not an official one on textures.minecraft.net");
        return URI.create("https://textures.minecraft.net" + url.getPath());
    }

    static SurfaceTexture fetch(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) skinUri(url).toURL().openConnection();
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.setInstanceFollowRedirects(false);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new Failure(Cause.HTTP, "textures.minecraft.net answered HTTP " + code);
            byte[] bytes;
            try (var in = connection.getInputStream()) {
                bytes = in.readNBytes(131073);
            }
            if (bytes.length > 131072) throw new Failure(Cause.IMAGE, "the skin file is larger than 128 KB");
            SurfaceTexture image;
            try {
                image = SurfaceTexture.read(bytes, 64);
            } catch (IOException unreadable) {
                throw new Failure(Cause.IMAGE, "the skin image could not be read (" + unreadable.getMessage() + ")");
            }
            if (image.width != 64 || (image.height != 32 && image.height != 64))
                throw new Failure(Cause.IMAGE, "the skin is " + image.width + "x" + image.height + " instead of 64x64 or 64x32");
            return image;
        } finally {
            connection.disconnect();
        }
    }

    @Override
    public void close() {
        closed = true;
        if (executor instanceof ExecutorService service) service.shutdownNow();
        skins.clear();
        attempts.clear();
    }
}
