package at.esoren.vystormsnapshot;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

/** Bounded, asynchronous official skin lookup. Missing skins retain the existing colour fallback. */
final class SkinCache implements AutoCloseable {
    private final Map<UUID, PlayerFigure.Skin> skins = new ConcurrentHashMap<>();
    private final Set<UUID> requested = ConcurrentHashMap.newKeySet();
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), r -> {
        Thread t = new Thread(r, "snapshot-assets");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean closed;
    private final Vystorm.vystorm_core.compat.PlatformCompatInterface platform;

    SkinCache(Vystorm.vystorm_core.compat.PlatformCompatInterface platform) { this.platform = platform; }

    PlayerFigure.Skin get(UUID id) {
        return skins.get(id);
    }
    void forget(UUID id) {
        skins.remove(id);
        requested.remove(id);
    }
    /** Main thread: only the immutable profile is handed to the background lookup. */
    void request(Player player) {
        if (closed || requested.size() >= 256 || !requested.add(player.getUniqueId())) return;
        UUID id = player.getUniqueId();
        PlayerProfile profile = platform.getPlayerProfile(player);
        if (profile.getTextures().getSkin() != null) {
            download(id, profile);
            return;
        }
        // Name-only profile also resolves skins on explicitly offline local test servers.
        Bukkit.createPlayerProfile(player.getName()).update().orTimeout(10, TimeUnit.SECONDS)
                .thenAccept(updated -> download(id, updated)).exceptionally(failure -> null);
    }
    private void download(UUID id, PlayerProfile profile) {
        var textures = profile.getTextures();
        URL url = textures.getSkin();
        if (url == null || closed) return;
        boolean slim = textures.getSkinModel() == PlayerTextures.SkinModel.SLIM;
        background(() -> {
            try {
                SurfaceTexture image = fetch(url);
                if (!closed && requested.contains(id)) skins.put(id, new PlayerFigure.Skin(image, slim));
            } catch (IOException | RuntimeException unavailable) { /* colour fallback, no main-thread retry loop */ }
        });
    }
    void background(Runnable action) {
        if (closed) return;
        try {
            executor.execute(action);
        } catch (RejectedExecutionException full) { /* bounded work queue */ }
    }
    static URI skinUri(URL url) throws IOException {
        if (!Set.of("http", "https").contains(url.getProtocol()) || !"textures.minecraft.net".equalsIgnoreCase(url.getHost())
                || (url.getPort() != -1 && url.getPort() != 443) || url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null
                || !url.getPath().matches("/texture/[0-9a-fA-F]{32,128}")) throw new IOException("Not an official skin texture URL");
        return URI.create("https://textures.minecraft.net" + url.getPath());
    }
    static SurfaceTexture fetch(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) skinUri(url).toURL().openConnection();
        connection.setConnectTimeout(3000);
        connection.setReadTimeout(3000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Skin unavailable");
            byte[] bytes;
            try (var in = connection.getInputStream()) {
                bytes = in.readNBytes(131073);
            }
            if (bytes.length > 131072) throw new IOException("Skin too large");
            SurfaceTexture image = SurfaceTexture.read(bytes, 64);
            if (image.width != 64 || (image.height != 32 && image.height != 64)) throw new IOException("Invalid skin dimensions");
            return image;
        } finally {
            connection.disconnect();
        }
    }
    @Override
    public void close() {
        closed = true;
        executor.shutdownNow();
        skins.clear();
        requested.clear();
    }
}
