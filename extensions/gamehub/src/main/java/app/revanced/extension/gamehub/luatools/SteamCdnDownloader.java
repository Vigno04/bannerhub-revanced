package app.revanced.extension.gamehub.luatools;

import android.content.Context;
import android.util.Log;

import com.luatools.javasteam.LuaToolsJavaSteamHook;
import com.luatools.javasteam.LuaToolsSteamEmu;
import com.luatools.javasteam.LuaToolsStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.revanced.extension.gamehub.components.BhTzstReader;
import app.revanced.extension.gamehub.gog.BhStorageHelper;
import app.revanced.extension.gamehub.gog.BhStoragePath;

/**
 * Streaming, flat-RAM SteamPipe CDN depot downloader for LuaTools (.lua + .manifest)
 * on BannerHub v6 (GameHub 6.3.1).
 *
 * Uses local .lua depot keys + pre-pushed .manifest files, downloads encrypted chunks
 * directly from Valve's SteamPipe CDN pool (IContentServerDirectoryService), decrypts
 * (AES-256 ECB+CBC), decompresses (PKZip / VZa LZMA / VSZa Zstd), verifies Steam
 * Adler32, writes directly at chunk offsets via RandomAccessFile, writes appmanifest_<id>.acf,
 * attaches SteamEmu/Goldberg runtime, and registers the game in db_game_library.db.
 */
public final class SteamCdnDownloader {

    private static final String TAG = "BhLuaSteamDL";
    private static final String STEAMPIPE_API =
            "https://api.steampowered.com/IContentServerDirectoryService/GetServersForSteamPipe/v1/?cell_id=0&max_servers=25";
    private static final String USER_AGENT = "Valve/Steam HTTP Client 1.0";

    public interface Callback {
        void onProgress(String msg, int pct);
        void onComplete(String installDir, String exePath);
        void onError(String msg);
        void onCancelled();
    }

    private static final class DepotJob {
        final int depotId;
        final long manifestId;
        final byte[] depotKey;
        final SteamManifestParser.Manifest manifest;

        DepotJob(int depotId, long manifestId, byte[] depotKey, SteamManifestParser.Manifest manifest) {
            this.depotId = depotId;
            this.manifestId = manifestId;
            this.depotKey = depotKey;
            this.manifest = manifest;
        }
    }

    private static final class ChunkTask {
        final int depotId;
        final byte[] depotKey;
        final File targetFile;
        final SteamManifestParser.ChunkData chunk;

        ChunkTask(int depotId, byte[] depotKey, File targetFile, SteamManifestParser.ChunkData chunk) {
            this.depotId = depotId;
            this.depotKey = depotKey;
            this.targetFile = targetFile;
            this.chunk = chunk;
        }
    }

    private SteamCdnDownloader() {}

    public static File resolveGameInstallDir(Context ctx, int appId, String fallbackName) {
        LuaToolsStore store = LuaToolsJavaSteamHook.get(ctx).getStore();
        String installDirName = null;

        // 1. Check existing ACF if already installed
        File internalAcf = new File(ctx.getFilesDir(), "Steam/steamapps/appmanifest_" + appId + ".acf");
        if (internalAcf.isFile()) {
            installDirName = parseInstallDirFromAcf(internalAcf);
        }

        // 2. Check PICS metadata
        if (installDirName == null || installDirName.isEmpty()) {
            JSONObject pics = store.getPicsInfo(appId);
            if (pics != null) {
                JSONObject cfg = pics.optJSONObject("config");
                if (cfg != null) installDirName = cfg.optString("installdir", "");
            }
        }

        // 3. Fallback to sanitized game name
        if (installDirName == null || installDirName.isEmpty()) {
            String name = (fallbackName != null && !fallbackName.isEmpty()) ? fallbackName : store.getGameName(appId);
            installDirName = name.replaceAll("[^a-zA-Z0-9_\\- ]", "").trim();
            if (installDirName.isEmpty()) installDirName = "App_" + appId;
        }

        // Honor BannerHub SD card toggle if enabled, else standard Steam/steamapps/common
        boolean useCustom = ctx.getSharedPreferences(BhStorageHelper.PREFS, 0)
                .getBoolean(BhStorageHelper.KEY_USE_CUSTOM, false);
        if (useCustom) {
            return BhStoragePath.getInstallDir(ctx, "steam_games", installDirName);
        }
        return new File(ctx.getFilesDir(), "Steam/steamapps/common/" + installDirName);
    }

    public static Runnable startDownload(
            Context ctx, int appId, int threads, Callback cb
    ) {
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final int workerCount = Math.max(1, Math.min(8, threads > 0 ? threads : 4));

        Thread t = new Thread(() -> runDownload(ctx.getApplicationContext(), appId, workerCount, cancelled, cb),
                "bh-steam-dl-" + appId);
        t.setDaemon(true);
        t.start();

        return () -> cancelled.set(true);
    }

    private static void runDownload(
            Context ctx, int appId, int workerCount, AtomicBoolean cancelled, Callback cb
    ) {
        try {
            cb.onProgress("Reading .lua & manifests…", 0);
            LuaToolsStore store = LuaToolsJavaSteamHook.get(ctx).getStore();
            if (!store.isLuaGame(appId)) {
                cb.onError("No .lua found for AppID " + appId);
                return;
            }

            String gameName = store.getGameName(appId);
            Map<Integer, Long> depotManifests = store.getWindowsDepotManifests(appId);
            if (depotManifests.isEmpty()) {
                cb.onError("No Windows depots/manifests found in " + appId + ".lua");
                return;
            }

            List<DepotJob> jobs = new ArrayList<>();
            long totalBytes = 0L;
            List<String> allManifestNames = new ArrayList<>();

            for (Map.Entry<Integer, Long> e : depotManifests.entrySet()) {
                if (cancelled.get()) { cb.onCancelled(); return; }
                int depotId = e.getKey();
                long manifestId = e.getValue();
                byte[] key = store.getDepotKey(depotId);
                if (key == null || key.length != 32) {
                    Log.w(TAG, "Skipping depot " + depotId + ": missing 32-byte key");
                    continue;
                }
                File mf = store.getManifestFile(depotId, manifestId);
                if (mf == null || !mf.isFile()) {
                    Log.w(TAG, "Missing manifest file for depot " + depotId + " (" + manifestId + ")");
                    continue;
                }
                byte[] raw = LuaToolsStore.readAllBytes(mf);
                SteamManifestParser.Manifest manifest = SteamManifestParser.parse(raw, key);
                jobs.add(new DepotJob(depotId, manifestId, key, manifest));
                for (SteamManifestParser.FileMapping fm : manifest.files) {
                    if (!fm.isDirectory()) {
                        totalBytes += fm.size;
                        allManifestNames.add(fm.filename);
                    }
                }
            }

            if (jobs.isEmpty()) {
                cb.onError("No usable .manifest + depot key pairs cached. Re-push game from LuaTools.");
                return;
            }

            File installDir = resolveGameInstallDir(ctx, appId, gameName);
            if (!installDir.exists() && !installDir.mkdirs()) {
                cb.onError("Cannot create install directory: " + installDir.getAbsolutePath());
                return;
            }
            String canonicalRoot = installDir.getCanonicalPath() + File.separator;

            cb.onProgress("Fetching Steam CDN servers…", 0);
            List<String> servers = fetchCdnHosts();
            if (servers.isEmpty()) {
                cb.onError("Could not reach SteamPipe CDN directory");
                return;
            }

            // Prepare directory structure and build chunk work queue (skipping already-verified files)
            cb.onProgress("Checking existing files…", 0);
            List<ChunkTask> tasks = new ArrayList<>();
            long alreadyDoneBytes = 0L;
            Map<Integer, Long> completedMap = loadCompletedManifests(installDir);

            for (DepotJob job : jobs) {
                if (cancelled.get()) { cb.onCancelled(); return; }
                boolean depotAlreadyMarked = Long.valueOf(job.manifestId).equals(completedMap.get(job.depotId));

                for (SteamManifestParser.FileMapping fm : job.manifest.files) {
                    if (cancelled.get()) { cb.onCancelled(); return; }
                    if (fm.filename == null || fm.filename.isEmpty()) continue;
                    File target = new File(installDir, fm.filename);
                    if (!target.getCanonicalPath().startsWith(canonicalRoot)) continue;

                    if (fm.isDirectory()) {
                        if (!target.exists()) target.mkdirs();
                        continue;
                    }
                    File parent = target.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();

                    if (fm.size == 0) {
                        if (!target.exists()) {
                            try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
                                raf.setLength(0);
                            }
                        }
                        continue;
                    }

                    // Fast resume: if depot completed or exact size & all chunks verify Adler32, skip
                    if (target.isFile() && target.length() == fm.size) {
                        if (depotAlreadyMarked || verifyFileChunks(target, fm)) {
                            alreadyDoneBytes += fm.size;
                            continue;
                        }
                    }

                    // Ensure file exists with target length
                    try (RandomAccessFile raf = new RandomAccessFile(target, "rw")) {
                        if (raf.length() != fm.size) {
                            raf.setLength(fm.size);
                        }
                    }

                    for (SteamManifestParser.ChunkData chunk : fm.chunks) {
                        // Skip individual chunk if existing bytes at offset already match Adler32
                        if (target.isFile() && target.length() >= chunk.offset + chunk.cbOriginal
                                && verifySingleChunkOnDisk(target, chunk)) {
                            alreadyDoneBytes += chunk.cbOriginal;
                        } else {
                            tasks.add(new ChunkTask(job.depotId, job.depotKey, target, chunk));
                        }
                    }
                }
            }

            final long totalExpected = Math.max(1L, totalBytes);
            final AtomicLong downloadedBytes = new AtomicLong(alreadyDoneBytes);
            final AtomicInteger serverRotator = new AtomicInteger(0);
            final Map<String, Object> fileLocks = new ConcurrentHashMap<>();
            final AtomicBoolean failed = new AtomicBoolean(false);
            final StringBuilder firstError = new StringBuilder();

            if (!tasks.isEmpty()) {
                ExecutorService pool = Executors.newFixedThreadPool(workerCount);
                List<Future<?>> futures = new ArrayList<>(tasks.size());

                for (final ChunkTask task : tasks) {
                    futures.add(pool.submit(() -> {
                        if (cancelled.get() || failed.get()) return;
                        try {
                            byte[] plain = downloadAndDecodeChunk(servers, serverRotator, task, cancelled);
                            if (cancelled.get() || plain == null) return;

                            Object lock = fileLocks.computeIfAbsent(task.targetFile.getAbsolutePath(), k -> new Object());
                            synchronized (lock) {
                                try (RandomAccessFile raf = new RandomAccessFile(task.targetFile, "rw")) {
                                    raf.seek(task.chunk.offset);
                                    raf.write(plain);
                                }
                            }
                            long done = downloadedBytes.addAndGet(plain.length);
                            int pct = (int) Math.min(99L, (done * 100L) / totalExpected);
                            cb.onProgress(formatBytes(done) + " / " + formatBytes(totalExpected), pct);
                        } catch (Throwable e) {
                            if (!cancelled.get() && failed.compareAndSet(false, true)) {
                                synchronized (firstError) {
                                    firstError.append(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                                }
                            }
                        }
                    }));
                }

                for (Future<?> f : futures) {
                    if (cancelled.get()) break;
                    try { f.get(); } catch (Throwable ignored) {}
                }
                pool.shutdownNow();
            }

            if (cancelled.get()) {
                cb.onCancelled();
                return;
            }
            if (failed.get()) {
                cb.onError("Download failed: " + firstError);
                return;
            }

            // Persist .DepotDownloader/installedManifestIDs + appmanifest_<appId>.acf
            saveCompletedManifests(installDir, jobs);
            writeAppManifestAcf(ctx, appId, gameName, installDir.getName(), jobs, totalBytes);

            // Detect executable & attach SteamEmu runtime + register in db_game_library.db
            String relExe = detectExecutable(ctx, appId, gameName, installDir, allManifestNames);
            String fullExe = relExe != null ? new File(installDir, relExe).getAbsolutePath() : "";

            try {
                LuaToolsSteamEmu.attachGameRuntime(ctx, appId, installDir);
            } catch (Throwable t) {
                Log.w(TAG, "attachGameRuntime warning: " + t.getMessage());
            }

            String coverUrl = BhLuaLibraryHelper.steamCapsuleUrl(appId);
            BhLuaLibraryHelper.saveGameMeta(ctx, appId, gameName, installDir.getAbsolutePath(), fullExe, coverUrl, true);
            if (!fullExe.isEmpty()) {
                BhLuaLibraryHelper.registerInLibrary(ctx, appId, gameName, fullExe, coverUrl);
            }

            cb.onProgress("Completed", 100);
            cb.onComplete(installDir.getAbsolutePath(), fullExe);
        } catch (Throwable t) {
            Log.e(TAG, "runDownload error", t);
            if (cancelled.get()) cb.onCancelled();
            else cb.onError(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
    }

    private static List<String> fetchCdnHosts() {
        List<String> hosts = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(STEAMPIPE_API).openConnection();
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            if (conn.getResponseCode() == 200) {
                String json = readStreamString(conn.getInputStream());
                JSONObject root = new JSONObject(json);
                JSONObject resp = root.optJSONObject("response");
                JSONArray arr = resp != null ? resp.optJSONArray("servers") : null;
                if (arr != null) {
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject s = arr.getJSONObject(i);
                        if (s.optInt("steam_china_only", 0) == 1) continue;
                        if (s.optInt("use_as_proxy", 0) == 1) continue;
                        String type = s.optString("type", "");
                        if (!"SteamCache".equals(type) && !"CDN".equals(type)) continue;
                        String host = s.optString("host", "");
                        if (!host.isEmpty() && !hosts.contains(host)) hosts.add(host);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "GetServersForSteamPipe failed: " + t.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
        if (hosts.isEmpty()) {
            hosts.add("fastly.steamstatic.com");
            hosts.add("cache1-ams1.steamcontent.com");
            hosts.add("cache2-fra1.steamcontent.com");
        }
        return hosts;
    }

    private static byte[] downloadAndDecodeChunk(
            List<String> servers, AtomicInteger rotator, ChunkTask task, AtomicBoolean cancelled
    ) throws Exception {
        String shaHex = bytesToHex(task.chunk.sha);
        int maxTries = Math.min(8, Math.max(4, servers.size()));
        Exception lastErr = null;

        for (int attempt = 0; attempt < maxTries; attempt++) {
            if (cancelled.get()) return null;
            String host = servers.get((rotator.getAndIncrement() & 0x7FFFFFFF) % servers.size());
            String urlStr = "https://" + host + "/depot/" + task.depotId + "/chunk/" + shaHex;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(urlStr).openConnection();
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setConnectTimeout(12000);
                conn.setReadTimeout(30000);
                int code = conn.getResponseCode();
                if (code != 200) {
                    lastErr = new Exception("HTTP " + code + " from " + host);
                    continue;
                }
                byte[] enc = readAllInputBytes(conn.getInputStream());
                if (task.chunk.cbCompressed > 0 && enc.length != task.chunk.cbCompressed) {
                    lastErr = new Exception("Truncated chunk from " + host);
                    continue;
                }
                byte[] dec = decryptChunk(task.depotKey, enc);
                byte[] plain = decompressChunk(dec, task.chunk.cbOriginal);
                if (plain.length != task.chunk.cbOriginal) {
                    lastErr = new Exception("Chunk size mismatch: " + plain.length + " != " + task.chunk.cbOriginal);
                    continue;
                }
                if (steamAdler32(plain) != task.chunk.crc) {
                    lastErr = new Exception("Chunk Adler32 mismatch for " + shaHex);
                    continue;
                }
                return plain;
            } catch (Exception e) {
                lastErr = e;
            } finally {
                if (conn != null) conn.disconnect();
            }
            try { Thread.sleep(Math.min(1500, 150L * (attempt + 1))); } catch (InterruptedException ignored) { return null; }
        }
        throw lastErr != null ? lastErr : new Exception("Failed downloading chunk " + shaHex);
    }

    private static byte[] decryptChunk(byte[] depotKey, byte[] enc) throws Exception {
        if (enc.length < 32) throw new IllegalArgumentException("Encrypted chunk too short");
        SecretKeySpec keySpec = new SecretKeySpec(depotKey, "AES");
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.DECRYPT_MODE, keySpec);
        byte[] iv = ecb.doFinal(enc, 0, 16);

        Cipher cbc = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cbc.init(Cipher.DECRYPT_MODE, keySpec, new IvParameterSpec(iv));
        return cbc.doFinal(enc, 16, enc.length - 16);
    }

    private static byte[] decompressChunk(byte[] dec, int expectedSize) throws Exception {
        if (dec.length >= 4 && dec[0] == 'V' && dec[1] == 'S' && dec[2] == 'Z' && dec[3] == 'a') {
            if (dec.length <= 8) throw new IllegalArgumentException("VSZa chunk too small");
            InputStream raw = new ByteArrayInputStream(dec, 8, dec.length - 8);
            InputStream zstdIn = BhTzstReader.openZstd(raw);
            if (zstdIn == null) throw new IllegalStateException("Host Zstd decompressor unavailable");
            try {
                return readAllInputBytes(zstdIn);
            } finally {
                zstdIn.close();
            }
        }
        if (dec.length >= 3 && dec[0] == 'V' && dec[1] == 'Z' && dec[2] == 'a') {
            if (dec.length < 22) throw new IllegalArgumentException("VZa chunk too small");
            byte[] props = Arrays.copyOfRange(dec, 7, 12);
            InputStream lzmaStream = new ByteArrayInputStream(dec, 12, dec.length - 22);
            return LzmaDecoder.decompressRaw(props, lzmaStream, expectedSize);
        }
        if (dec.length >= 4 && dec[0] == 'P' && dec[1] == 'K' && dec[2] == 3 && dec[3] == 4) {
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(dec))) {
                ZipEntry e = zis.getNextEntry();
                if (e == null) throw new IllegalArgumentException("Empty PKZip chunk");
                return readAllInputBytes(zis);
            }
        }
        throw new IllegalArgumentException("Unsupported Steam chunk compression header");
    }

    private static boolean verifyFileChunks(File f, SteamManifestParser.FileMapping fm) {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] buf = new byte[1024 * 1024];
            for (SteamManifestParser.ChunkData c : fm.chunks) {
                if (c.cbOriginal <= 0) continue;
                if (c.cbOriginal > buf.length) buf = new byte[c.cbOriginal];
                raf.seek(c.offset);
                int read = 0;
                while (read < c.cbOriginal) {
                    int r = raf.read(buf, read, c.cbOriginal - read);
                    if (r < 0) return false;
                    read += r;
                }
                if (steamAdler32(buf, c.cbOriginal) != c.crc) return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean verifySingleChunkOnDisk(File f, SteamManifestParser.ChunkData c) {
        if (c.cbOriginal <= 0) return true;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] buf = new byte[c.cbOriginal];
            raf.seek(c.offset);
            int read = 0;
            while (read < c.cbOriginal) {
                int r = raf.read(buf, read, c.cbOriginal - read);
                if (r < 0) return false;
                read += r;
            }
            return steamAdler32(buf, c.cbOriginal) == c.crc;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int steamAdler32(byte[] data) {
        return steamAdler32(data, data.length);
    }

    private static int steamAdler32(byte[] data, int len) {
        long a = 0;
        long b = 0;
        for (int i = 0; i < len; i++) {
            a = (a + (data[i] & 0xFF)) % 65521L;
            b = (b + a) % 65521L;
        }
        return (int) (a | (b << 16));
    }

    private static Map<Integer, Long> loadCompletedManifests(File installDir) {
        Map<Integer, Long> map = new HashMap<>();
        File f = new File(installDir, ".DepotDownloader/depot.config");
        if (!f.isFile()) return map;
        try {
            JSONObject obj = new JSONObject(new String(LuaToolsStore.readAllBytes(f), "UTF-8"));
            JSONObject ids = obj.optJSONObject("installedManifestIDs");
            if (ids != null) {
                java.util.Iterator<String> it = ids.keys();
                while (it.hasNext()) {
                    String k = it.next();
                    map.put(Integer.parseInt(k), Long.parseLong(ids.optString(k, "0")));
                }
            }
        } catch (Throwable ignored) {}
        return map;
    }

    private static void saveCompletedManifests(File installDir, List<DepotJob> jobs) {
        try {
            File ddDir = new File(installDir, ".DepotDownloader");
            if (!ddDir.exists()) ddDir.mkdirs();
            JSONObject ids = new JSONObject();
            for (DepotJob j : jobs) {
                ids.put(String.valueOf(j.depotId), String.valueOf(j.manifestId));
            }
            JSONObject root = new JSONObject();
            root.put("installedManifestIDs", ids);
            LuaToolsStore.writeAllBytes(new File(ddDir, "depot.config"), root.toString(2).getBytes("UTF-8"));
            new File(ddDir, ".download_complete").createNewFile();
        } catch (Throwable t) {
            Log.w(TAG, "saveCompletedManifests warning: " + t.getMessage());
        }
    }

    private static void writeAppManifestAcf(
            Context ctx, int appId, String name, String installDirName, List<DepotJob> jobs, long totalBytes
    ) {
        try {
            File steamApps = new File(ctx.getFilesDir(), "Steam/steamapps");
            if (!steamApps.exists()) steamApps.mkdirs();
            StringBuilder sb = new StringBuilder();
            sb.append("\"AppState\"\n{\n");
            sb.append("\t\"appid\"\t\t\"").append(appId).append("\"\n");
            sb.append("\t\"Universe\"\t\t\"1\"\n");
            sb.append("\t\"name\"\t\t\"").append(name.replace("\"", "")).append("\"\n");
            sb.append("\t\"StateFlags\"\t\t\"4\"\n");
            sb.append("\t\"installdir\"\t\t\"").append(installDirName.replace("\"", "")).append("\"\n");
            sb.append("\t\"SizeOnDisk\"\t\t\"").append(totalBytes).append("\"\n");
            sb.append("\t\"InstalledDepots\"\n\t{\n");
            for (DepotJob j : jobs) {
                sb.append("\t\t\"").append(j.depotId).append("\"\n\t\t{\n");
                sb.append("\t\t\t\"manifest\"\t\t\"").append(j.manifestId).append("\"\n");
                sb.append("\t\t\t\"size\"\t\t\"").append(j.manifest.cbDiskOriginal).append("\"\n");
                sb.append("\t\t}\n");
            }
            sb.append("\t}\n}\n");
            LuaToolsStore.writeAllBytes(new File(steamApps, "appmanifest_" + appId + ".acf"), sb.toString().getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "writeAppManifestAcf warning: " + t.getMessage());
        }
    }

    public static String detectExecutable(
            Context ctx, int appId, String gameName, File installDir, List<String> manifestNames
    ) {
        // 1. Check PICS launch config first
        try {
            JSONObject pics = LuaToolsJavaSteamHook.get(ctx).getStore().getPicsInfo(appId);
            if (pics != null) {
                JSONObject cfg = pics.optJSONObject("config");
                JSONObject launch = cfg != null ? cfg.optJSONObject("launch") : null;
                if (launch != null) {
                    java.util.Iterator<String> it = launch.keys();
                    while (it.hasNext()) {
                        JSONObject entry = launch.optJSONObject(it.next());
                        if (entry == null) continue;
                        String exe = entry.optString("executable", "").replace('\\', '/');
                        JSONObject ecfg = entry.optJSONObject("config");
                        String os = ecfg != null ? ecfg.optString("oslist", "windows") : "windows";
                        if (!exe.isEmpty() && os.toLowerCase(Locale.ROOT).contains("windows")) {
                            File cand = new File(installDir, exe);
                            if (cand.isFile()) return exe;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 2. Scan disk / manifest names
        List<String> exes = new ArrayList<>();
        if (installDir != null && installDir.isDirectory()) {
            collectExes(installDir, installDir, exes, 0);
        }
        if (exes.isEmpty() && manifestNames != null) {
            for (String n : manifestNames) {
                if (n.toLowerCase(Locale.ROOT).endsWith(".exe") && !isBadExe(n)) exes.add(n);
            }
        }
        if (exes.isEmpty()) return null;

        String key = (gameName != null ? gameName : "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        String best = null;
        int bestScore = Integer.MIN_VALUE;
        for (String e : exes) {
            String base = e.substring(e.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT)
                    .replace(".exe", "").replaceAll("[^a-z0-9]", "");
            int score = -e.split("/").length * 10;
            if (!base.isEmpty() && (key.contains(base) || base.contains(key))) score += 50;
            if (base.contains("64")) score += 1;
            if (score > bestScore) {
                bestScore = score;
                best = e;
            }
        }
        return best;
    }

    private static void collectExes(File root, File dir, List<String> out, int depth) {
        File[] fs = dir.listFiles();
        if (fs == null || depth > 3) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                collectExes(root, f, out, depth + 1);
            } else if (f.getName().toLowerCase(Locale.ROOT).endsWith(".exe") && !isBadExe(f.getName())) {
                out.add(f.getAbsolutePath().substring(root.getAbsolutePath().length() + 1).replace('\\', '/'));
            }
        }
    }

    private static boolean isBadExe(String n) {
        String l = n.toLowerCase(Locale.ROOT);
        String[] bad = {"unins", "crashhandler", "crashpad", "vc_redist", "vcredist", "dxsetup", "dotnet",
                "ue4prereq", "directx", "unitycrashhandler", "setup", "redist", "launcher_helper"};
        for (String b : bad) if (l.contains(b)) return true;
        return false;
    }

    private static String parseInstallDirFromAcf(File acf) {
        try {
            String txt = new String(LuaToolsStore.readAllBytes(acf), "UTF-8");
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"installdir\"\\s+\"([^\"]+)\"").matcher(txt);
            if (m.find()) return m.group(1);
        } catch (Throwable ignored) {}
        return null;
    }

    private static byte[] readAllInputBytes(InputStream in) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int r;
        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
        return bos.toByteArray();
    }

    private static String readStreamString(InputStream in) throws Exception {
        return new String(readAllInputBytes(in), "UTF-8");
    }

    private static String bytesToHex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >>> 4) & 0xF, 16));
            sb.append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    private static String formatBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return String.format(Locale.US, "%.1f KB", b / 1024.0);
        if (b < 1024L * 1024 * 1024) return String.format(Locale.US, "%.1f MB", b / (1024.0 * 1024.0));
        return String.format(Locale.US, "%.2f GB", b / (1024.0 * 1024.0 * 1024.0));
    }
}
