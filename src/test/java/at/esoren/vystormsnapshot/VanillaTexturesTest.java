package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The download from Mojang with every outside part replaced: a fake version list, fake version files and a tiny
 * self-made client jar served from an in-memory HTTP fake (no network), a clock, a worker that runs at once and a
 * delay that only records. No Mojang file is part of this test.
 */
class VanillaTexturesTest {
    private static final String META = "https://piston-meta.mojang.com/", DATA = "https://piston-data.mojang.com/";

    @TempDir Path dir;
    private Path pack, cache;
    private final Map<String, byte[]> served = new HashMap<>();
    private final Map<String, String> redirects = new HashMap<>();
    private final List<String> requested = new ArrayList<>();
    private final List<String> infos = new ArrayList<>(), warnings = new ArrayList<>();
    private final List<Runnable> delayed = new ArrayList<>();
    private final List<Long> delays = new ArrayList<>();
    private final AtomicLong now = new AtomicLong(1_800_000_000_000L);
    private final AtomicInteger loaded = new AtomicInteger();
    private AtomicFilesInterface files = AtomicFilesInterface.DEFAULT;
    private IOException networkDown;

    @BeforeEach void paths() {
        pack = dir.resolve("textures.zip");
        cache = dir.resolve("cache");
    }

    // ------------------------------------------------------------------ fixture

    /** A 16x16 PNG made here, not taken from Minecraft. */
    private static byte[] png() throws IOException {
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 16; y++) for (int x = 0; x < 16; x++) image.setRGB(x, y, 0xFF000000 | (x * 16) << 8 | y * 16);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /** A fake client jar: wanted block files, an escape attempt and things that must stay out. */
    static byte[] fakeJar(String marker) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "net/minecraft/client/Main.class", new byte[]{(byte) 0xCA, (byte) 0xFE});
            put(zip, "assets/minecraft/textures/block/fake_stone.png", png());
            put(zip, "assets/minecraft/textures/block/fake_stone.png.mcmeta", "{\"animation\":{}}".getBytes(StandardCharsets.UTF_8));
            put(zip, "assets/minecraft/models/block/fake_stone.json",
                    ("{\"parent\":\"block/cube_all\",\"textures\":{\"all\":\"block/fake_stone\"},\"marker\":\"" + marker + "\"}").getBytes(StandardCharsets.UTF_8));
            put(zip, "assets/minecraft/blockstates/fake_stone.json", "{\"variants\":{\"\":{\"model\":\"block/fake_stone\"}}}".getBytes(StandardCharsets.UTF_8));
            put(zip, "assets/minecraft/textures/block/../../../../../escape.png", png());
            put(zip, "assets/minecraft/textures/item/fake_apple.png", png());
            put(zip, "assets/minecraft/sounds.json", "{}".getBytes(StandardCharsets.UTF_8));
            put(zip, "pack.png", png());
        }
        return bytes.toByteArray();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(data);
        zip.closeEntry();
    }

    static String sha1(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes));
    }

    /** Serves a version: its JSON on piston-meta and its jar on piston-data; returns the version file's address. */
    private String version(String id, byte[] jar, String jarSha1, long size, String jarUrl) throws Exception {
        String url = jarUrl != null ? jarUrl : DATA + "v1/objects/" + sha1(jar) + "/client.jar";
        if (jarUrl == null) served.put(url, jar);
        String json = "{\"id\":\"" + id + "\",\"downloads\":{\"client\":{\"sha1\":\"" + (jarSha1 != null ? jarSha1 : sha1(jar)) + "\",\"size\":" + size
                + ",\"url\":\"" + url + "\"},\"server\":{\"sha1\":\"" + "0".repeat(40) + "\",\"size\":1,\"url\":\"" + DATA + "server.jar\"}}}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        String versionUrl = META + "v1/packages/" + sha1(bytes) + "/" + id + ".json";
        served.put(versionUrl, bytes);
        return versionUrl;
    }

    private String version(String id, byte[] jar) throws Exception { return version(id, jar, null, jar.length, null); }

    /** The version list with the given versions (id, file address); the latest release is 26.2. */
    private void manifest(String... idsAndUrls) throws Exception {
        StringBuilder versions = new StringBuilder();
        for (int i = 0; i < idsAndUrls.length; i += 2) {
            if (versions.length() > 0) versions.append(',');
            byte[] file = served.get(idsAndUrls[i + 1]);
            versions.append("{\"id\":\"").append(idsAndUrls[i]).append("\",\"type\":\"release\",\"url\":\"").append(idsAndUrls[i + 1])
                    .append("\",\"sha1\":\"").append(file == null ? "0".repeat(40) : sha1(file)).append("\"}");
        }
        served.put(VanillaTextures.MANIFEST.toString(), ("{\"latest\":{\"release\":\"26.2\",\"snapshot\":\"26w50a\"},\"versions\":[" + versions + "]}")
                .getBytes(StandardCharsets.UTF_8));
    }

    private VanillaTextures downloader() {
        VanillaTextures.Http http = uri -> {
            requested.add(uri.toString());
            if (networkDown != null) throw networkDown;
            String target = redirects.get(uri.toString());
            if (target != null) return new VanillaTextures.Answer(302, target, null, null);
            byte[] body = served.get(uri.toString());
            if (body == null) return new VanillaTextures.Answer(404, null, null, null);
            return new VanillaTextures.Answer(200, null, new ByteArrayInputStream(body), null);
        };
        return new VanillaTextures(cache, files, http, Runnable::run, (millis, task) -> { delays.add(millis); delayed.add(task); }, now::get,
                infos::add, warnings::add);
    }

    private VanillaTextures.Settings settings(String serverVersion) { return settings(true, true, "auto", serverVersion); }

    private VanillaTextures.Settings settings(boolean download, boolean accepted, String version, String serverVersion) {
        return new VanillaTextures.Settings(download, accepted, version, serverVersion, pack);
    }

    private boolean start(VanillaTextures textures, VanillaTextures.Settings settings) { return textures.start(settings, loaded::incrementAndGet); }

    private List<String> zipNames() throws IOException {
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            List<String> names = new ArrayList<>();
            zip.stream().forEach(entry -> names.add(entry.getName()));
            return names;
        }
    }

    private long cacheFiles() throws IOException {
        if (!Files.isDirectory(cache)) return 0;
        try (Stream<Path> list = Files.list(cache)) { return list.count(); }
    }

    private JsonObject sidecar() throws IOException {
        return JsonParser.parseString(Files.readString(dir.resolve("textures.source.json"))).getAsJsonObject();
    }

    private long jarRequests() { return requested.stream().filter(url -> url.startsWith(DATA)).count(); }

    // ------------------------------------------------------------------ consent

    @Test void givenOnlyOneOfTheTwoKeys_whenStarted_thenNothingIsDownloaded() throws Exception {
        manifest("26.3", version("26.3", fakeJar("a")));
        VanillaTextures textures = downloader();
        assertFalse(start(textures, settings(true, false, "auto", "26.3")));
        assertEquals(VanillaTextures.State.CONSENT, textures.status().state());
        assertFalse(start(textures, settings(false, true, "auto", "26.3")));
        assertEquals(VanillaTextures.State.OFF, textures.status().state());
        assertFalse(start(textures, settings(false, false, "auto", "26.3")));
        assertEquals(VanillaTextures.State.OFF, textures.status().state());
        assertEquals(List.of(), requested, "no request at all");
        assertFalse(Files.exists(pack));
        assertEquals(0, cacheFiles());
        assertEquals(0, loaded.get());
    }

    // ------------------------------------------------------------------ the happy path

    @Test void givenBothKeys_whenStarted_thenManifestVersionAndClientAreFetchedAndOnlyBlockFilesAreKept() throws Exception {
        byte[] jar = fakeJar("a");
        String versionUrl = version("26.3", jar);
        manifest("26.2", version("26.2", fakeJar("old")), "26.3", versionUrl);
        VanillaTextures textures = downloader();

        assertTrue(start(textures, settings("26.3")));

        assertEquals(List.of(VanillaTextures.MANIFEST.toString(), versionUrl, DATA + "v1/objects/" + sha1(jar) + "/client.jar"), requested);
        assertEquals(Set.of("assets/minecraft/textures/block/fake_stone.png", "assets/minecraft/textures/block/fake_stone.png.mcmeta",
                "assets/minecraft/models/block/fake_stone.json", "assets/minecraft/blockstates/fake_stone.json"), new HashSet<>(zipNames()));
        JsonObject source = sidecar();
        assertEquals("26.3", source.get("version").getAsString());
        assertEquals(sha1(jar), source.get("sha1").getAsString());
        assertEquals("downloaded from Mojang", source.get("source").getAsString());
        assertEquals(sha1(Files.readAllBytes(pack)), source.get("zip-sha1").getAsString());
        assertFalse(source.has("replaces"));
        assertTrue(source.get("date").getAsString().startsWith("2027-01-15"));
        assertEquals(0, cacheFiles(), "the client jar is deleted");
        assertEquals(1, loaded.get(), "the textures are loaded again");
        VanillaTextures.Status status = textures.status();
        assertEquals(VanillaTextures.State.DONE, status.state());
        assertEquals("26.3", status.version());
        assertTrue(BlockTextures.load(pack).size() > 0, "the written zip is a usable pack");
        assertTrue(infos.get(0).startsWith("Downloading the Minecraft 26.3 client jar (0.0 MB) from piston-data.mojang.com"), infos.get(0));
        assertTrue(infos.get(infos.size() - 1).contains("4 files (1 block textures)"), infos.get(infos.size() - 1));
        assertEquals(List.of(), warnings);

        // the next start (same version) needs no network and loads nothing again
        requested.clear();
        assertTrue(start(textures, settings("26.3")));
        assertEquals(List.of(), requested);
        assertEquals(VanillaTextures.State.DONE, textures.status().state());
        assertEquals(1, loaded.get());
    }

    @Test void givenLatestReleaseAsAnExplicitSetting_whenStarted_thenMojangsNewestReleaseIsTaken() throws Exception {
        byte[] jar = fakeJar("latest");
        manifest("26.2", version("26.2", jar), "26.3", version("26.3", fakeJar("b")));
        VanillaTextures textures = downloader();
        start(textures, settings(true, true, "latest-release", "27.9"));
        assertEquals("26.2", sidecar().get("version").getAsString());
        assertEquals(VanillaTextures.State.DONE, textures.status().state());
        // asked again: the list is read, the zip stays
        requested.clear();
        start(textures, settings(true, true, "latest-release", "27.9"));
        assertEquals(List.of(VanillaTextures.MANIFEST.toString()), requested);
        assertEquals(1, loaded.get());
    }

    // ------------------------------------------------------------------ refusals

    @Test void givenAServerVersionMojangDoesNotList_whenStarted_thenNothingIsDownloadedAndNoOtherVersionIsTaken() throws Exception {
        manifest("26.2", version("26.2", fakeJar("a")));
        VanillaTextures textures = downloader();
        start(textures, settings("26.3"));
        assertEquals(List.of(VanillaTextures.MANIFEST.toString()), requested, "no version file, no jar");
        assertFalse(Files.exists(pack));
        VanillaTextures.Status status = textures.status();
        assertEquals(VanillaTextures.State.FAILED, status.state());
        assertTrue(status.problem().contains("Minecraft 26.3 is not in Mojang's version list"), status.problem());
        assertTrue(status.problem().contains("latest-release") && status.problem().contains("now 26.2"), status.problem());
        assertEquals(0, status.retryAt(), "the same list gives the same answer: no retry");
        assertEquals(List.of(), delayed);
        assertEquals(1, warnings.size());
    }

    @Test void givenAWrongSha1_whenDownloaded_thenTheJarIsThrownAwayAndTriedAgainAFewTimes() throws Exception {
        byte[] jar = fakeJar("a");
        manifest("26.3", version("26.3", jar, "1".repeat(40), jar.length, null));
        VanillaTextures textures = downloader();
        start(textures, settings("26.3"));
        assertFalse(Files.exists(pack));
        assertEquals(0, cacheFiles(), "the temp file is removed");
        VanillaTextures.Status status = textures.status();
        assertEquals(VanillaTextures.State.FAILED, status.state());
        assertTrue(status.problem().contains("SHA-1"), status.problem());
        assertEquals(now.get() + 30_000, status.retryAt());
        assertTrue(warnings.get(0).contains("Trying again in 30 s (try 2 of 4)"), warnings.get(0));
        for (int i = 0; i < 10 && !delayed.isEmpty(); i++) delayed.remove(0).run();
        assertEquals(4, jarRequests(), "one start tries at most four times");
        assertEquals(List.of(30_000L, 300_000L, 1_800_000L), delays);
        assertEquals(4, warnings.size(), "each failure once");
        assertTrue(warnings.get(3).contains("No further try until the next start or /snapshot reload"), warnings.get(3));
        assertEquals(0, textures.status().retryAt());
        assertEquals(0, cacheFiles());
        assertEquals(0, loaded.get());
    }

    @Test void givenNoConnection_whenStarted_thenTheReasonIsNamedAndARetryPlanned() throws Exception {
        networkDown = new SocketTimeoutException("Connect timed out");
        VanillaTextures textures = downloader();
        start(textures, settings("26.3"));
        assertTrue(textures.status().problem().contains("piston-meta.mojang.com could not be reached (SocketTimeoutException: Connect timed out)"),
                textures.status().problem());
        assertEquals(1, delayed.size());
        // a reload while the retry waits starts at once and drops the planned retry
        networkDown = null;
        manifest("26.3", version("26.3", fakeJar("a")));
        start(textures, settings("26.3"));
        assertEquals(VanillaTextures.State.DONE, textures.status().state());
        int before = requested.size();
        delayed.remove(0).run();
        assertEquals(before, requested.size(), "the old retry does nothing");
    }

    @Test void givenAJarOverTheSizeCap_whenStarted_thenItIsNotDownloaded() throws Exception {
        byte[] jar = fakeJar("a");
        manifest("26.3", version("26.3", jar, null, 90L * 1024 * 1024, null));
        VanillaTextures textures = downloader();
        start(textures, settings("26.3"));
        assertEquals(0, jarRequests());
        assertTrue(textures.status().problem().contains("more than the limit of 80.0 MB"), textures.status().problem());
        assertEquals(List.of(), delayed);
    }

    @Test void givenAnAnswerLongerThanAnnounced_whenDownloaded_thenItStopsAtTheAnnouncedSize() throws Exception {
        byte[] jar = fakeJar("a");
        manifest("26.3", version("26.3", jar, null, 100, null));
        VanillaTextures textures = downloader();
        start(textures, settings("26.3"));
        assertTrue(textures.status().problem().contains("larger than"), textures.status().problem());
        assertFalse(Files.exists(pack));
        assertEquals(0, cacheFiles());
    }

    @Test void givenAddressesOutsideTheAllowList_whenStarted_thenTheyAreRefusedWithoutARequest() throws Exception {
        // a version file on another host
        manifest("26.3", "https://piston-meta.mojang.com.evil.example/26.3.json");
        VanillaTextures textures = downloader();
        start(textures, settings("26.3"));
        assertEquals(List.of(VanillaTextures.MANIFEST.toString()), requested);
        assertTrue(textures.status().problem().startsWith("refused to download from https://piston-meta.mojang.com.evil.example"), textures.status().problem());
        assertEquals(List.of(), delayed, "a refused host is not tried again");

        // a client jar over plain http
        requested.clear();
        byte[] jar = fakeJar("a");
        manifest("26.3", version("26.3", jar, null, jar.length, "http://piston-data.mojang.com/client.jar"));
        start(textures, settings("26.3"));
        assertEquals(0, jarRequests());
        assertTrue(textures.status().problem().startsWith("refused to download from http://piston-data.mojang.com"), textures.status().problem());

        // a redirect to another host
        requested.clear();
        String jarUrl = DATA + "v1/objects/x/client.jar";
        redirects.put(jarUrl, "https://downloads.example.net/client.jar");
        manifest("26.3", version("26.3", jar, null, jar.length, jarUrl));
        start(textures, settings("26.3"));
        assertTrue(requested.stream().noneMatch(url -> url.contains("example.net")), requested.toString());
        assertTrue(textures.status().problem().startsWith("refused to download from https://downloads.example.net"), textures.status().problem());
        assertFalse(Files.exists(pack));

        for (String bad : List.of("https://user@piston-data.mojang.com/x", "https://piston-data.mojang.com:8443/x", "ftp://piston-data.mojang.com/x",
                "https://mojang.com/x", "https://PISTON-DATA.mojang.com.example/x"))
            assertThrows(VanillaTextures.Failure.class, () -> VanillaTextures.allowed(URI.create(bad)), bad);
        for (String good : List.of(DATA + "x", META + "x", "https://launcher.mojang.com/x", "https://piston-data.mojang.com:443/x"))
            assertDoesNotThrow(() -> VanillaTextures.allowed(URI.create(good)), good);
    }

    @Test void givenOddEntryNames_whenFiltered_thenOnlyPlainBlockFilesPass() {
        for (String name : List.of("assets/minecraft/textures/block/stone.png", "assets/minecraft/textures/block/sub/a.png.mcmeta",
                "assets/minecraft/models/block/stone.json", "assets/minecraft/blockstates/stone.json"))
            assertTrue(VanillaTextures.wanted(name), name);
        for (String name : List.of("assets/minecraft/textures/block/../../../../escape.png", "/assets/minecraft/textures/block/a.png",
                "assets/minecraft/textures/block//a.png", "assets/minecraft/textures/block/./a.png", "assets\\minecraft\\textures\\block\\a.png",
                "C:/assets/minecraft/textures/block/a.png", "assets/minecraft/textures/block/", "assets/minecraft/textures/item/apple.png",
                "assets/minecraft/textures/block/a.txt", "assets/minecraft/models/item/a.json", "assets/minecraft/blockstates/a.png",
                "assets/othermod/textures/block/a.png", "net/minecraft/client/Main.class"))
            assertFalse(VanillaTextures.wanted(name), name);
    }

    // ------------------------------------------------------------------ whose zip

    @Test void givenAnAdminZip_whenStarted_thenItIsNeverOverwritten() throws Exception {
        manifest("26.3", version("26.3", fakeJar("a")));
        byte[] own = "my own pack".getBytes(StandardCharsets.UTF_8);
        Files.write(pack, own);
        VanillaTextures textures = downloader();
        start(textures, settings("26.3"));
        assertEquals(VanillaTextures.State.OWN_FILE, textures.status().state());
        assertArrayEquals(own, Files.readAllBytes(pack));
        assertEquals(List.of(), requested);

        // a sidecar that does not belong to this zip (the admin put a new zip over the plugin's one)
        Files.writeString(dir.resolve("textures.source.json"), VanillaTextures.sourceJson(new VanillaTextures.Source("26.2", "a".repeat(40), "b".repeat(40), null, "x", 4)));
        start(textures, settings("26.3"));
        assertEquals(VanillaTextures.State.OWN_FILE, textures.status().state());
        assertArrayEquals(own, Files.readAllBytes(pack));
        assertEquals(List.of(), requested);
        assertEquals(0, loaded.get());
    }

    @Test void givenTheZipThePluginWrote_whenTheServerVersionChanges_thenItIsReplaced() throws Exception {
        manifest("26.2", version("26.2", fakeJar("old")), "26.3", version("26.3", fakeJar("new")));
        VanillaTextures textures = downloader();
        start(textures, settings("26.2"));
        byte[] first = Files.readAllBytes(pack);
        assertEquals("26.2", sidecar().get("version").getAsString());

        start(textures, settings("26.3"));
        assertEquals("26.3", sidecar().get("version").getAsString());
        assertFalse(Arrays.equals(first, Files.readAllBytes(pack)));
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            assertTrue(new String(zip.getInputStream(zip.getEntry("assets/minecraft/models/block/fake_stone.json")).readAllBytes(), StandardCharsets.UTF_8).contains("new"));
        }
        assertEquals(2, loaded.get());
        assertEquals(0, cacheFiles());
    }

    @Test void givenABrokenJar_whenUnpacked_thenNoHalfZipIsVisibleAndTheTempFileIsGone() throws Exception {
        byte[] jar = fakeJar("a");
        // cut inside the data of a later entry, after some wanted files were already copied
        int at = new String(jar, StandardCharsets.ISO_8859_1).indexOf("escape.png") + "escape.png".length() + 12;
        byte[] cut = Arrays.copyOf(jar, at);
        manifest("26.3", version("26.3", cut));
        VanillaTextures textures = downloader();
        start(textures, settings("26.3"));
        assertEquals(VanillaTextures.State.FAILED, textures.status().state());
        assertTrue(textures.status().problem().contains("could not be unpacked"), textures.status().problem());
        assertFalse(Files.exists(pack));
        assertEquals(0, cacheFiles());
        try (Stream<Path> list = Files.list(dir)) { assertEquals(List.of(cache), list.toList(), "nothing but the empty cache folder"); }
    }

    @Test void givenAWriteThatFails_whenTheZipIsReplaced_thenTheOldZipStaysWholeAndIsStillKnownAsThePluginsOwn() throws Exception {
        manifest("26.2", version("26.2", fakeJar("old")), "26.3", version("26.3", fakeJar("new")));
        start(downloader(), settings("26.2"));
        byte[] first = Files.readAllBytes(pack);

        AtomicFilesInterface real = files;
        files = new AtomicFilesInterface() {
            @Override public void write(Path file, byte[] bytes) throws IOException {
                if (file.getFileName().toString().equals("textures.zip")) throw new IOException("No space left on device");
                real.write(file, bytes);
            }
        };
        VanillaTextures failing = downloader();
        start(failing, settings("26.3"));
        assertEquals(VanillaTextures.State.FAILED, failing.status().state());
        assertTrue(failing.status().problem().contains("No space left on device"), failing.status().problem());
        assertArrayEquals(first, Files.readAllBytes(pack), "the old zip is untouched");
        assertEquals(0, cacheFiles());

        files = real;
        VanillaTextures working = downloader();
        start(working, settings("26.3"));
        assertEquals(VanillaTextures.State.DONE, working.status().state(), String.valueOf(working.status().problem()));
        assertEquals("26.3", sidecar().get("version").getAsString());
        assertFalse(sidecar().has("replaces"));
    }

    // ------------------------------------------------------------------ one run at a time

    @Test void givenTwoStartsBeforeTheWorkerRuns_whenTheWorkerRuns_thenTheJarIsFetchedOnce() throws Exception {
        manifest("26.3", version("26.3", fakeJar("a")));
        List<Runnable> queue = new ArrayList<>();
        VanillaTextures.Http http = uri -> {
            requested.add(uri.toString());
            return new VanillaTextures.Answer(200, null, new ByteArrayInputStream(served.get(uri.toString())), null);
        };
        VanillaTextures textures = new VanillaTextures(cache, files, http, queue::add, (m, t) -> {}, now::get, infos::add, warnings::add);
        start(textures, settings("26.3"));
        start(textures, settings("26.3"));
        while (!queue.isEmpty()) queue.remove(0).run();
        assertEquals(1, jarRequests());
        assertEquals(1, loaded.get());
    }

    @Test void givenBukkitVersions_whenRead_thenTheMinecraftVersionIsTheFirstPart() {
        assertEquals("26.3", VanillaTextures.minecraftVersion("26.3-R0.1-SNAPSHOT"));
        assertEquals("1.20.6", VanillaTextures.minecraftVersion("1.20.6-R0.1-SNAPSHOT"));
        assertNull(VanillaTextures.minecraftVersion(null));
        assertEquals("26.3", VanillaTextures.local(settings("26.3")));
        assertEquals("26.1", VanillaTextures.local(settings(true, true, "26.1", "26.3")));
        assertNull(VanillaTextures.local(settings(true, true, "latest-release", "26.3")));
        assertEquals(Path.of("x", "textures.source.json"), VanillaTextures.sidecar(Path.of("x", "textures.zip")));
    }
}
