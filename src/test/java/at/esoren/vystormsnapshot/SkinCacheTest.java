package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.bukkit.entity.Player;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;
import org.junit.jupiter.api.Test;

/**
 * The skin loader with every outside part replaced: profiles, the name lookup, the download, the clock and the worker
 * (runs at once). The URL is the shape Mojang's session server hands out today: plain http, textures.minecraft.net,
 * /texture/ and a 64 character hex hash.
 */
class SkinCacheTest {
    static final String MOJANG_URL = "http://textures.minecraft.net/texture/292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680";

    private final AtomicLong now = new AtomicLong(1_000_000);
    private final List<String> warnings = new ArrayList<>();
    private final AtomicInteger downloads = new AtomicInteger();
    private IOException failWith = new SocketTimeoutException("Connect timed out");

    private SkinCache cache(String skinUrl) {
        return new SkinCache(player -> profile(skinUrl), name -> CompletableFuture.completedFuture(profile(null)), url -> {
            downloads.incrementAndGet();
            if (failWith != null) throw failWith;
            return new SurfaceTexture(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB));
        }, Runnable::run, now::get, warnings::add);
    }

    private static PlayerProfile profile(String skinUrl) {
        PlayerProfile profile = mock(PlayerProfile.class);
        PlayerTextures textures = mock(PlayerTextures.class);
        when(profile.getTextures()).thenReturn(textures);
        try {
            when(textures.getSkin()).thenReturn(skinUrl == null ? null : URI.create(skinUrl).toURL());
        } catch (Exception e) { throw new IllegalStateException(e); }
        when(textures.getSkinModel()).thenReturn(PlayerTextures.SkinModel.CLASSIC);
        return profile;
    }

    private static Player player() {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn("Alex");
        return player;
    }

    @Test void givenMojangsUrlShape_whenAsked_thenItIsFetchedOverHttpsFromTheSameHost() throws Exception {
        assertEquals("https://textures.minecraft.net/texture/292009a4925b58f02c77dadc3ecef07ea4c7472f64e0fdc32ce5522489362680",
                SkinCache.skinUri(URI.create(MOJANG_URL).toURL()).toString());
        // Mojang hashes are hex numbers without leading zeros: 63 characters happen
        assertDoesNotThrow(() -> SkinCache.skinUri(URI.create("http://textures.minecraft.net/texture/" + "b".repeat(63)).toURL()));
        for (String bad : new String[]{"http://textures.minecraft.net.evil.example/texture/" + "a".repeat(64), "file:///texture/" + "a".repeat(64),
                "https://user@textures.minecraft.net/texture/" + "a".repeat(64), "https://textures.minecraft.net/texture/../x"}) {
            SkinCache.Failure failure = assertThrows(SkinCache.Failure.class, () -> SkinCache.skinUri(URI.create(bad).toURL()), bad);
            assertEquals(SkinCache.Cause.URL, failure.cause);
        }
    }

    @Test void givenADownloadThatWorks_whenRequested_thenTheSkinIsCachedAndNothingIsLogged() {
        failWith = null;
        SkinCache cache = cache(MOJANG_URL);
        Player player = player();
        cache.request(player);
        assertNotNull(cache.get(player.getUniqueId()));
        assertEquals(1, cache.status().cached());
        assertNull(cache.status().lastFailure());
        assertEquals(List.of(), warnings);
        cache.request(player);
        assertEquals(1, downloads.get(), "a cached skin is not downloaded again for every photo");
    }

    @Test void givenADownloadThatFails_whenRequestedForManyPhotos_thenOneWarningWithTheReasonAndRetriedAfterTheBackoff() {
        SkinCache cache = cache(MOJANG_URL);
        Player player = player();
        cache.request(player);
        assertNull(cache.get(player.getUniqueId()));
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("Connect timed out"), warnings.getFirst());
        assertTrue(warnings.getFirst().contains("textures.minecraft.net"), warnings.getFirst());
        for (int photo = 0; photo < 50; photo++) cache.request(player);
        assertEquals(1, downloads.get(), "no new download before the backoff is over");
        assertEquals(1, warnings.size(), "never one warning per photo");
        assertTrue(cache.status().lastFailure().contains("Connect timed out"));

        now.addAndGet(SkinCache.BACKOFF_MILLIS[0]);
        failWith = null;
        cache.request(player);
        assertEquals(2, downloads.get(), "tried again after the backoff");
        assertNotNull(cache.get(player.getUniqueId()));
    }

    @Test void givenRepeatedFailures_thenTheWaitGrowsAndTheSameCauseWarnsAtMostOncePerInterval() {
        SkinCache cache = cache(MOJANG_URL);
        Player player = player();
        cache.request(player);
        now.addAndGet(SkinCache.BACKOFF_MILLIS[0]);
        cache.request(player);
        assertEquals(2, downloads.get());
        now.addAndGet(SkinCache.BACKOFF_MILLIS[0]);
        cache.request(player);
        assertEquals(2, downloads.get(), "the second wait is longer than the first");
        now.addAndGet(SkinCache.BACKOFF_MILLIS[1]);
        cache.request(player);
        assertEquals(3, downloads.get());
        assertEquals(1, warnings.size(), "the same cause again within the interval stays quiet");
        // another cause is a new warning
        failWith = new SkinCache.Failure(SkinCache.Cause.HTTP, "textures.minecraft.net answered HTTP 403");
        Player other = player();
        cache.request(other);
        assertEquals(2, warnings.size());
        assertTrue(warnings.get(1).contains("HTTP 403"));
        now.addAndGet(SkinCache.WARN_INTERVAL_MILLIS);
        failWith = new SocketTimeoutException("Read timed out");
        cache.request(player());
        assertEquals(3, warnings.size(), "after the interval the cause warns again");
    }

    @Test void givenAFailure_whenRefreshed_thenEveryOnlinePlayerIsAskedAgainAtOnce() {
        SkinCache cache = cache(MOJANG_URL);
        Player player = player();
        cache.request(player);
        assertEquals(1, downloads.get());
        failWith = null;
        cache.refresh(List.of(player));
        assertEquals(2, downloads.get(), "/snapshot reload does not wait for the backoff");
        assertNotNull(cache.get(player.getUniqueId()));
        cache.refresh(List.of(player));
        assertEquals(3, downloads.get(), "a reload also fetches cached skins again (changed skins)");
        assertNotNull(cache.get(player.getUniqueId()));
    }

    @Test void givenAProfileWithoutSkin_whenTheNameLookupHasNoneEither_thenTheReasonIsNamed() {
        SkinCache cache = cache(null);
        Player player = player();
        cache.request(player);
        assertEquals(0, downloads.get());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("no skin"), warnings.getFirst());
        assertTrue(cache.status().lastFailure().contains("no skin"));
    }

    @Test void givenAPlayerWhoLeft_whenTheDownloadEndsLater_thenNothingIsKept() {
        failWith = null;
        List<Runnable> queued = new ArrayList<>();
        SkinCache cache = new SkinCache(p -> profile(MOJANG_URL), name -> CompletableFuture.completedFuture(profile(null)),
                url -> new SurfaceTexture(new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB)), queued::add, now::get, warnings::add);
        Player player = player();
        cache.request(player);
        cache.forget(player.getUniqueId());
        queued.forEach(Runnable::run);
        assertNull(cache.get(player.getUniqueId()));
        assertEquals(0, cache.status().cached());
    }

    @Test void givenAFullWorkQueue_thenItCountsAsAFailureAndIsRetried() {
        failWith = null;
        SkinCache cache = new SkinCache(p -> profile(MOJANG_URL), name -> CompletableFuture.completedFuture(profile(null)),
                url -> new SurfaceTexture(new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB)),
                task -> { throw new java.util.concurrent.RejectedExecutionException("full"); }, now::get, warnings::add);
        Player player = player();
        cache.request(player);
        assertEquals(1, warnings.size());
        assertNotNull(cache.status().lastFailure());
    }

    @Test void givenTheRealFetch_whenTheUrlIsNotOfficial_thenItFailsBeforeAnyConnection() {
        assertThrows(SkinCache.Failure.class, () -> SkinCache.fetch(new URL("http://localhost/texture/" + "a".repeat(64))));
    }
}
