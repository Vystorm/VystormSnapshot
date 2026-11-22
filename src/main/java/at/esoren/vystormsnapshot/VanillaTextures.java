package at.esoren.vystormsnapshot;

import Vystorm.vystorm_core._coreMethods.dataStorage.AtomicFilesInterface;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Opt-in download of the vanilla block textures from Mojang (1.20.3). Nothing is fetched unless both
 * {@code rendering.vanilla-textures.download} and {@code rendering.vanilla-textures.accept-download} are true. The flow
 * runs on its own thread, one run at a time: Mojang's version list, the version's JSON, the client jar (HTTPS, exact
 * host allow-list, size cap, SHA-1 checked) into {@code cache/}, then only the block textures, block models and
 * blockstates go into a new {@code textures.zip}, written in one step. A sidecar file {@code textures.source.json}
 * marks the zip as the plugin's own; a zip without it (or with other contents) is the admin's and is never replaced.
 * HTTP, the clock, the worker, the delay and the file writes are injected so tests run without a network.
 */
final class VanillaTextures implements AutoCloseable {
    static final URI MANIFEST = URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
    /** The only hosts anything is fetched from, also after a redirect. */
    static final Set<String> HOSTS = Set.of("piston-meta.mojang.com", "piston-data.mojang.com", "launcher.mojang.com");
    static final long MAX_JAR_BYTES = 80L * 1024 * 1024;
    static final int MAX_JSON_BYTES = 4 * 1024 * 1024;
    /** One file inside the jar; block textures are a few KB each. */
    static final int MAX_ENTRY_BYTES = 2 * 1024 * 1024;
    static final long MAX_ZIP_BYTES = 64L * 1024 * 1024;
    /** Waits before the 2nd, 3rd and 4th try of one start; after that the plugin waits for the next start or reload. */
    static final long[] BACKOFF_MILLIS = {30_000, 300_000, 1_800_000};
    static final String TEXTURES = "assets/minecraft/textures/block/", MODELS = "assets/minecraft/models/block/",
            STATES = "assets/minecraft/blockstates/";
    static final String AUTO = "auto", LATEST = "latest-release";

    /** One GET. {@code location} is the Location header (redirects are never followed by the connection itself). */
    interface Http { Answer open(URI uri) throws IOException; }

    static final class Answer implements Closeable {
        final int status;
        final String location;
        final InputStream body;
        private final Runnable onClose;

        Answer(int status, String location, InputStream body, Runnable onClose) {
            this.status = status;
            this.location = location;
            this.body = body;
            this.onClose = onClose;
        }

        @Override public void close() throws IOException {
            try { if (body != null) body.close(); } finally { if (onClose != null) onClose.run(); }
        }
    }

    /** Runs a task later on the download thread. */
    interface Delay { void after(long millis, Runnable task); }

    enum State { OFF, CONSENT, OWN_FILE, CHECKING, DOWNLOADING, DONE, FAILED }

    /**
     * For {@code /snapshot status}. {@code retryAt} is 0 when no further try is planned; {@code size} is the jar size in
     * bytes while downloading.
     */
    record Status(State state, String version, int percent, long size, String problem, long retryAt, String date) {
        static final Status OFF = new Status(State.OFF, null, 0, 0, null, 0, null);
    }

    /** What config.yml and the server say, read on the server thread at start and reload. */
    record Settings(boolean download, boolean accepted, String version, String serverVersion, Path pack) {}

    /** Why a run stopped; {@code retry} false means trying again would give the same answer. */
    static final class Failure extends Exception {
        final boolean retry;
        Failure(boolean retry, String reason) { super(reason, null, false, false); this.retry = retry; }
    }

    /** Contents of the sidecar file. {@code replaces} is the previous zip's SHA-1 while a new one is being written. */
    record Source(String version, String jarSha1, String zipSha1, String replaces, String date, int entries) {}

    private final Path cache;
    private final AtomicFilesInterface files;
    private final Http http;
    private final Executor worker;
    private final Delay delay;
    private final LongSupplier clock;
    private final Consumer<String> info, warn;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile Status status = Status.OFF;
    private volatile boolean closed;
    private volatile Closeable active;

    VanillaTextures(Path cache, AtomicFilesInterface files, java.util.logging.Logger logger) {
        this(cache, files, executor(), logger);
    }

    private VanillaTextures(Path cache, AtomicFilesInterface files, ScheduledExecutorService service, java.util.logging.Logger logger) {
        this(cache, files, VanillaTextures::connect, service, (millis, task) -> service.schedule(task, millis, TimeUnit.MILLISECONDS),
                System::currentTimeMillis, logger::info, logger::warning);
    }

    VanillaTextures(Path cache, AtomicFilesInterface files, Http http, Executor worker, Delay delay, LongSupplier clock,
                    Consumer<String> info, Consumer<String> warn) {
        this.cache = cache;
        this.files = files;
        this.http = http;
        this.worker = worker;
        this.delay = delay;
        this.clock = clock;
        this.info = info;
        this.warn = warn;
    }

    private static ScheduledExecutorService executor() {
        ScheduledThreadPoolExecutor service = new ScheduledThreadPoolExecutor(1, r -> {
            Thread thread = new Thread(r, "snapshot-vanilla-textures");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        });
        service.setRemoveOnCancelPolicy(true);
        return service;
    }

    Status status() { return status; }

    /** The sidecar next to the pack: {@code textures.zip} gets {@code textures.source.json}. */
    static Path sidecar(Path pack) {
        String name = pack.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return pack.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".source.json");
    }

    /** {@code 26.3-R0.1-SNAPSHOT} gives {@code 26.3}. */
    static String minecraftVersion(String bukkitVersion) {
        if (bukkitVersion == null || bukkitVersion.isBlank()) return null;
        int dash = bukkitVersion.indexOf('-');
        return (dash < 0 ? bukkitVersion : bukkitVersion.substring(0, dash)).trim();
    }

    /**
     * Server thread, at start and on every reload. Returns true when a check (and if needed a download) was started,
     * after which {@code loaded} runs on the download thread once a new textures.zip is in place.
     */
    boolean start(Settings settings, Runnable loaded) {
        if (closed) return false;
        if (!settings.download()) { generation.incrementAndGet(); if (!running.get()) status = Status.OFF; return false; }
        if (!settings.accepted()) { generation.incrementAndGet(); if (!running.get()) status = new Status(State.CONSENT, null, 0, 0, null, 0, null); return false; }
        if (running.get()) return true; // one run at a time; the running one reports when it is done
        int run = generation.incrementAndGet(); // a planned retry of an earlier start is dropped
        return submit(() -> attempt(settings, loaded, run, 1));
    }

    private boolean submit(Runnable task) {
        try {
            worker.execute(task);
            return true;
        } catch (RejectedExecutionException shutDown) {
            return false;
        }
    }

    private void attempt(Settings settings, Runnable loaded, int run, int number) {
        if (closed || run != generation.get() || !running.compareAndSet(false, true)) return;
        try {
            if (check(settings, number)) loaded.run();
        } catch (Failure failure) {
            boolean again = failure.retry && number <= BACKOFF_MILLIS.length && !closed;
            long wait = again ? BACKOFF_MILLIS[number - 1] : 0;
            status = new Status(State.FAILED, status.version(), 0, 0, failure.getMessage(), again ? clock.getAsLong() + wait : 0, null);
            warn.accept("Block texture download failed: " + failure.getMessage() + (again
                    ? ". Trying again in " + SkinCache.duration(wait) + " (try " + (number + 1) + " of " + (BACKOFF_MILLIS.length + 1) + ")."
                    : ". No further try until the next start or /snapshot reload."));
            if (again) delay.after(wait, () -> attempt(settings, loaded, run, number + 1));
        } catch (RuntimeException failure) {
            status = new Status(State.FAILED, status.version(), 0, 0, String.valueOf(failure), 0, null);
            warn.accept("Block texture download failed: " + failure + ". No further try until the next start or /snapshot reload.");
        } finally {
            running.set(false);
        }
    }

    /** True when a new textures.zip was written. */
    private boolean check(Settings settings, int number) throws Failure {
        Path pack = settings.pack();
        Path side = sidecar(pack);
        status = new Status(State.CHECKING, null, 0, 0, null, 0, null);
        Source source = readSource(side);
        String current = null;
        if (Files.exists(pack)) {
            if (!Files.isRegularFile(pack) || source == null) { ownFile(); return false; }
            String sha = sha1(pack);
            if (Objects.equals(sha, source.zipSha1())) current = sha;
            else if (!Objects.equals(sha, source.replaces())) { ownFile(); return false; }
            // else: the plugin's previous zip, the replacement was cut short: write it again
        }
        String wanted = local(settings);
        if (current != null && wanted != null && wanted.equals(source.version())) {
            status = new Status(State.DONE, source.version(), 100, 0, null, 0, source.date());
            return false;
        }
        JsonObject manifest = json(MANIFEST, null, "Mojang's version list");
        if (wanted == null) wanted = latest(manifest);
        if (current != null && wanted.equals(source.version())) {
            status = new Status(State.DONE, source.version(), 100, 0, null, 0, source.date());
            return false;
        }
        download(settings, manifest, wanted, current, number);
        return true;
    }

    private void ownFile() { status = new Status(State.OWN_FILE, null, 0, 0, null, 0, null); }

    /** The version asked for without a network: the server's or the one set in config; null for latest-release. */
    static String local(Settings settings) {
        String set = settings.version() == null ? AUTO : settings.version().trim();
        if (set.isEmpty() || set.equalsIgnoreCase(AUTO)) return settings.serverVersion();
        if (set.equalsIgnoreCase(LATEST)) return null;
        return set;
    }

    private static String latest(JsonObject manifest) throws Failure {
        try {
            return manifest.getAsJsonObject("latest").get("release").getAsString();
        } catch (RuntimeException broken) {
            throw new Failure(false, "Mojang's version list names no latest release");
        }
    }

    private void download(Settings settings, JsonObject manifest, String version, String previousZip, int number) throws Failure {
        if (version == null || version.isBlank())
            throw new Failure(false, "the server did not say its Minecraft version; set rendering.vanilla-textures.version to one, for example 26.2");
        JsonObject entry = null;
        try {
            for (JsonElement element : manifest.getAsJsonArray("versions"))
                if (element.isJsonObject() && version.equals(string(element.getAsJsonObject(), "id"))) { entry = element.getAsJsonObject(); break; }
        } catch (RuntimeException broken) {
            throw new Failure(false, "Mojang's version list could not be read (" + broken.getMessage() + ")");
        }
        if (entry == null) {
            String latest;
            try { latest = latest(manifest); } catch (Failure none) { latest = "unknown"; }
            throw new Failure(false, "Minecraft " + version + " is not in Mojang's version list, so nothing was downloaded. Set rendering.vanilla-textures.version"
                    + " to a version from that list, or to " + LATEST + " to take Mojang's newest release (now " + latest + ")");
        }
        JsonObject versionJson = json(uri(string(entry, "url")), string(entry, "sha1"), "the version file of Minecraft " + version);
        JsonObject client;
        String url, sha1;
        long size;
        try {
            client = versionJson.getAsJsonObject("downloads").getAsJsonObject("client");
            url = client.get("url").getAsString();
            sha1 = client.get("sha1").getAsString().toLowerCase(Locale.ROOT);
            size = client.get("size").getAsLong();
        } catch (RuntimeException broken) {
            throw new Failure(false, "the version file of Minecraft " + version + " names no client download");
        }
        if (!sha1.matches("[0-9a-f]{40}")) throw new Failure(false, "the version file of Minecraft " + version + " has no valid SHA-1 for the client");
        if (size <= 0 || size > MAX_JAR_BYTES)
            throw new Failure(false, "the client jar of Minecraft " + version + " is " + mb(size) + ", more than the limit of " + mb(MAX_JAR_BYTES));
        URI jarUri = uri(url);
        allowed(jarUri);

        status = new Status(State.DOWNLOADING, version, 0, size, null, 0, null);
        info.accept("Downloading the Minecraft " + version + " client jar (" + mb(size) + ") from " + jarUri.getHost()
                + " for the camera's block textures" + (number > 1 ? " (try " + number + ")" : "") + ". Its textures stay on this server.");
        long started = clock.getAsLong();
        Path temp = cache.resolve("client-" + version.replaceAll("[^0-9A-Za-z._-]", "_") + ".jar.part");
        try {
            try {
                Files.createDirectories(cache);
                removeLeftovers();
            } catch (IOException failure) {
                throw new Failure(false, "the folder " + cache + " could not be prepared (" + failure.getMessage() + ")");
            }
            MessageDigest digest = digest();
            int[] reported = {0};
            try (OutputStream out = Files.newOutputStream(temp)) {
                long written = fetch(jarUri, size, "the client jar", new OutputStream() {
                    long count;
                    @Override public void write(int b) throws IOException { write(new byte[]{(byte) b}, 0, 1); }
                    @Override public void write(byte[] bytes, int off, int len) throws IOException {
                        out.write(bytes, off, len);
                        digest.update(bytes, off, len);
                        count += len;
                        int percent = (int) Math.min(100, count * 100 / size);
                        status = new Status(State.DOWNLOADING, version, percent, size, null, 0, null);
                        if (percent / 25 > reported[0] && percent < 100) {
                            reported[0] = percent / 25;
                            info.accept("Block texture download: " + percent + " % (" + mb(count) + " of " + mb(size) + ")");
                        }
                    }
                });
                if (written != size) throw new Failure(true, "the client jar ended after " + written + " of " + size + " bytes");
            } catch (IOException failure) {
                throw new Failure(true, "the client jar could not be stored in " + cache + " (" + failure.getMessage() + ")");
            }
            String got = HexFormat.of().formatHex(digest.digest());
            if (!got.equals(sha1)) throw new Failure(true, "the client jar's SHA-1 is " + got + " instead of " + sha1 + "; the file was thrown away");

            Extracted extracted = extract(temp);
            String zipSha = HexFormat.of().formatHex(digest().digest(extracted.zip()));
            String date = Instant.ofEpochMilli(clock.getAsLong()).toString();
            Source source = new Source(version, sha1, zipSha, previousZip, date, extracted.entries());
            Path pack = settings.pack();
            try {
                // the sidecar first: should the server stop between the two writes, the old zip is still known as ours
                files.write(sidecar(pack), sourceJson(source).getBytes(StandardCharsets.UTF_8));
                files.write(pack, extracted.zip());
                files.write(sidecar(pack), sourceJson(new Source(version, sha1, zipSha, null, date, extracted.entries())).getBytes(StandardCharsets.UTF_8));
            } catch (IOException | RuntimeException failure) {
                throw new Failure(true, pack + " could not be written (" + failure.getMessage() + ")");
            }
            status = new Status(State.DONE, version, 100, 0, null, 0, date);
            long seconds = Math.max(0, (clock.getAsLong() - started) / 1000);
            info.accept("Block textures of Minecraft " + version + " are ready: " + extracted.entries() + " files (" + extracted.textures()
                    + " block textures) in " + pack + " after " + seconds + " s. The client jar was deleted again.");
        } finally {
            try { Files.deleteIfExists(temp); } catch (IOException stays) { warn.accept("Could not delete " + temp + ": " + stays.getMessage()); }
        }
    }

    record Extracted(byte[] zip, int entries, int textures) {}

    /** Only textures/block, models/block and blockstates; every other name (and any odd path) is left out. */
    static boolean wanted(String name) {
        if (name == null || name.isEmpty() || name.startsWith("/") || name.contains("\\") || name.contains(":") || name.endsWith("/")) return false;
        for (String part : name.split("/", -1)) if (part.isEmpty() || part.equals(".") || part.equals("..")) return false;
        return (name.startsWith(TEXTURES) && (name.endsWith(".png") || name.endsWith(".png.mcmeta")))
                || (name.startsWith(MODELS) && name.endsWith(".json"))
                || (name.startsWith(STATES) && name.endsWith(".json"));
    }

    static Extracted extract(Path jar) throws Failure {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(8 * 1024 * 1024);
        int entries = 0, textures = 0;
        long total = 0;
        Set<String> seen = new HashSet<>();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar)); ZipOutputStream out = new ZipOutputStream(bytes)) {
            for (ZipEntry entry; (entry = in.getNextEntry()) != null; ) {
                String name = entry.getName();
                if (entry.isDirectory() || !wanted(name) || !seen.add(name)) continue;
                byte[] data = in.readNBytes(MAX_ENTRY_BYTES + 1);
                if (data.length > MAX_ENTRY_BYTES) continue;
                total += data.length;
                if (total > MAX_ZIP_BYTES) throw new Failure(false, "the client jar holds more than " + mb(MAX_ZIP_BYTES) + " of block files");
                ZipEntry copy = new ZipEntry(name);
                copy.setTime(entry.getTime());
                out.putNextEntry(copy);
                out.write(data);
                out.closeEntry();
                entries++;
                if (name.startsWith(TEXTURES) && name.endsWith(".png")) textures++;
            }
        } catch (IOException | IllegalArgumentException broken) {
            throw new Failure(true, "the client jar could not be unpacked (" + broken.getMessage() + ")");
        }
        if (textures == 0) throw new Failure(false, "the client jar has no block textures in " + TEXTURES);
        return new Extracted(bytes.toByteArray(), entries, textures);
    }

    /** Removes what an interrupted earlier run left in cache/ (only the plugin's own client-*.jar.part files). */
    private void removeLeftovers() throws IOException {
        try (DirectoryStream<Path> left = Files.newDirectoryStream(cache, "client-*.jar.part")) {
            for (Path path : left) Files.deleteIfExists(path);
        }
    }

    // ------------------------------------------------------------------ HTTP

    static void allowed(URI uri) throws Failure {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || !HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT)) || uri.getRawUserInfo() != null
                || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new Failure(false, "refused to download from " + (uri == null ? "nowhere" : uri.getScheme() + "://" + uri.getHost())
                    + ": only HTTPS from " + String.join(", ", new java.util.TreeSet<>(HOSTS)) + " is allowed");
    }

    private static URI uri(String text) throws Failure {
        try {
            return URI.create(text);
        } catch (RuntimeException broken) {
            throw new Failure(false, "Mojang's files name an address that is not one (" + text + ")");
        }
    }

    private JsonObject json(URI uri, String sha1, String what) throws Failure {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        fetch(uri, MAX_JSON_BYTES, what, body);
        byte[] bytes = body.toByteArray();
        if (sha1 != null && !HexFormat.of().formatHex(digest().digest(bytes)).equalsIgnoreCase(sha1))
            throw new Failure(true, what + " does not match its SHA-1 in Mojang's version list");
        try {
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException broken) {
            throw new Failure(true, what + " could not be read (" + broken.getMessage() + ")");
        }
    }

    /** Copies one answer into {@code sink}, at most {@code cap} bytes; follows up to 3 redirects inside the allow-list. */
    private long fetch(URI start, long cap, String what, OutputStream sink) throws Failure {
        URI uri = start;
        for (int hop = 0; ; hop++) {
            allowed(uri);
            if (closed) throw new Failure(false, "the plugin was stopped");
            Answer answer;
            try {
                answer = http.open(uri);
            } catch (IOException failure) {
                throw new Failure(true, uri.getHost() + " could not be reached (" + failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : ": " + failure.getMessage()) + ")");
            }
            active = answer;
            try (answer) {
                int code = answer.status;
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    if (hop >= 3) throw new Failure(false, uri.getHost() + " sent too many redirects for " + what);
                    if (answer.location == null) throw new Failure(true, uri.getHost() + " sent a redirect without an address for " + what);
                    try { uri = uri.resolve(answer.location); } catch (RuntimeException broken) { throw new Failure(false, "a redirect to a broken address: " + answer.location); }
                    continue;
                }
                if (code != 200) throw new Failure(code >= 500 || code == 429 || code == 408, uri.getHost() + " answered HTTP " + code + " for " + what);
                long count = 0;
                byte[] buffer = new byte[64 * 1024];
                for (int read; (read = answer.body.read(buffer)) >= 0; ) {
                    count += read;
                    if (count > cap) throw new Failure(false, what + " is larger than " + mb(cap) + "; download stopped");
                    sink.write(buffer, 0, read);
                }
                return count;
            } catch (IOException failure) {
                throw new Failure(true, "the download of " + what + " from " + uri.getHost() + " broke off (" + failure.getClass().getSimpleName()
                        + (failure.getMessage() == null ? "" : ": " + failure.getMessage()) + ")");
            } finally {
                active = null;
            }
        }
    }

    /** The real connection: HTTPS, 10 s to connect, 30 s between two reads, no automatic redirects. */
    static Answer connect(URI uri) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "VystormSnapshot");
        int code = connection.getResponseCode();
        InputStream body = code == 200 ? connection.getInputStream() : null;
        return new Answer(code, connection.getHeaderField("Location"), body, connection::disconnect);
    }

    // ------------------------------------------------------------------ files

    Source readSource(Path side) {
        if (!Files.isRegularFile(side)) return null;
        try {
            JsonObject json = JsonParser.parseString(Files.readString(side, StandardCharsets.UTF_8)).getAsJsonObject();
            String version = string(json, "version"), zip = string(json, "zip-sha1");
            if (version == null || zip == null) return null;
            int entries = json.has("entries") ? json.get("entries").getAsInt() : 0;
            return new Source(version, string(json, "sha1"), zip, string(json, "replaces"), string(json, "date"), entries);
        } catch (IOException | RuntimeException broken) {
            return null;
        }
    }

    static String sourceJson(Source source) {
        JsonObject json = new JsonObject();
        json.addProperty("source", "downloaded from Mojang");
        json.addProperty("version", source.version());
        json.addProperty("sha1", source.jarSha1());
        json.addProperty("zip-sha1", source.zipSha1());
        if (source.replaces() != null) json.addProperty("replaces", source.replaces());
        json.addProperty("date", source.date());
        json.addProperty("entries", source.entries());
        json.addProperty("note", "Written by Vystorm Snapshot. While this file is here and matches textures.zip, the plugin may replace that zip"
                + " when the server's Minecraft version changes. Delete this file to keep the zip as your own.");
        return new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n";
    }

    private static String string(JsonObject json, String key) {
        JsonElement value = json.get(key);
        return value == null || !value.isJsonPrimitive() ? null : value.getAsString();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-1");
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException(missing);
        }
    }

    private static String sha1(Path file) throws Failure {
        MessageDigest digest = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int read; (read = in.read(buffer)) >= 0; ) digest.update(buffer, 0, read);
        } catch (IOException failure) {
            throw new Failure(true, file + " could not be read (" + failure.getMessage() + ")");
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String mb(long bytes) { return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0)); }

    @Override
    public void close() {
        closed = true;
        generation.incrementAndGet();
        Closeable open = active;
        if (open != null) try { open.close(); } catch (IOException ignored) { /* shutting down */ }
        if (worker instanceof ExecutorService service) service.shutdownNow();
    }
}
