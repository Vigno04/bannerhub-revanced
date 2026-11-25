package com.luatools.javasteam;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Drop-in exported ContentProvider for ANY Android Steam emulator (GameNative, Pluvia,
 * BannerHub, GameHub, Winlator, etc.) to receive .lua files, streamed .manifest files,
 * and game fix zips from the LuaTools Android Companion App.
 *
 * Register in AndroidManifest.xml:
 * <pre>{@code
 * <provider
 *     android:name="com.luatools.javasteam.LuaToolsBridgeProvider"
 *     android:authorities="${applicationId}.bh_lua_bridge"
 *     android:exported="true" />
 * }</pre>
 *
 * Subclass and override {@link #onGameAdded} or {@link #resolveGameInstallDir} only if your
 * app stores Steam games in a non-default directory or needs a UI refresh callback.
 */
public class LuaToolsBridgeProvider extends ContentProvider {

    public static final int BRIDGE_VERSION = 2;

    private static final Set<String> KNOWN_PROXY_DLLS = new HashSet<>(Arrays.asList(
            "onlinefix64", "onlinefix", "winmm", "winhttp", "version", "dxgi", "dinput8",
            "steam_api64", "steam_api", "d3d11", "xinput1_4", "xinput1_3", "dsound", "bink2w64"
    ));

    @Override
    public boolean onCreate() {
        return true;
    }

    /** Override to customize the display name reported to the Companion App. */
    protected String getAppDisplayName(Context ctx) {
        try {
            return ctx.getApplicationInfo().loadLabel(ctx.getPackageManager()).toString();
        } catch (Throwable t) {
            return ctx.getPackageName();
        }
    }

    /** Override if your emulator stores Steam games outside files/Steam/steamapps/common/. */
    protected File resolveGameInstallDir(Context ctx, int appId, String fallbackName) {
        File common = new File(ctx.getFilesDir(), "Steam/steamapps/common");
        LuaToolsStore store = LuaToolsJavaSteamHook.get(ctx).getStore();
        JSONObject pics = store.getPicsInfo(appId);
        if (pics != null) {
            JSONObject cfg = pics.optJSONObject("config");
            String installDir = cfg != null ? cfg.optString("installdir", "") : "";
            if (!installDir.isEmpty()) {
                return new File(common, installDir);
            }
        }
        String safe = (fallbackName != null ? fallbackName : "")
                .replaceAll("[^a-zA-Z0-9_\\- ]", "").trim();
        if (safe.isEmpty()) safe = "App_" + appId;
        return new File(common, safe);
    }

    /** Optional hook called right after a .lua + manifests are saved. */
    protected void onGameAdded(Context ctx, int appId, String name, LuaToolsStore store) {}

    /** Optional hook called when a fix zip introduces proxy DLLs needing Wine overrides. */
    protected void onFixDllOverrides(Context ctx, int appId, Set<String> dllStems) {}

    /** Override to return the Steam client root directory (containing steam.exe) for BST/OST attachment. */
    protected File resolveSteamClientDir(Context ctx) { return null; }

    /** Override to return the Windows-side path to stplug-in for opensteamtool.toml. */
    protected String resolveSteamLuaPathWin(Context ctx) {
        File dir = LuaToolsJavaSteamHook.get(ctx).getStore().getStPlugInDir();
        return "Z:" + dir.getAbsolutePath().replace('/', '\\');
    }

    /** Optional hook called after BST/OST DLLs are placed in the Steam client dir (e.g. patch launch script). */
    protected void onSteamClientAttached(Context ctx, File steamClientDir, String mode) {}

    /** Optional hook called after BST/OST DLLs are removed from the Steam client dir. */
    protected void onSteamClientDetached(Context ctx, File steamClientDir) {}

    protected LuaToolsSteamEmu.HostAdapter createSteamEmuHostAdapter() {
        return new LuaToolsSteamEmu.HostAdapter() {
            @Override public File resolveSteamClientDir(Context ctx) {
                return LuaToolsBridgeProvider.this.resolveSteamClientDir(ctx);
            }
            @Override public String resolveSteamLuaPathWin(Context ctx) {
                return LuaToolsBridgeProvider.this.resolveSteamLuaPathWin(ctx);
            }
            @Override public File resolveGameInstallDir(Context ctx, int appId) {
                return LuaToolsBridgeProvider.this.resolveGameInstallDir(ctx, appId, "");
            }
            @Override public void onSteamClientAttached(Context ctx, File steamClientDir, String mode) {
                LuaToolsBridgeProvider.this.onSteamClientAttached(ctx, steamClientDir, mode);
            }
            @Override public void onSteamClientDetached(Context ctx, File steamClientDir) {
                LuaToolsBridgeProvider.this.onSteamClientDetached(ctx, steamClientDir);
            }
        };
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        Context ctx = getContext();
        if (ctx == null) throw new FileNotFoundException("No context");
        List<String> segs = uri.getPathSegments();
        if (segs == null || segs.size() < 2) throw new FileNotFoundException("Invalid URI: " + uri);

        String kind = segs.get(0);
        if ("manifest_in".equals(kind)) {
            String key = segs.get(1).replaceAll("[^0-9_]", "");
            if (!key.contains("_")) throw new FileNotFoundException("Invalid manifest key");
            File dir = LuaToolsJavaSteamHook.get(ctx).getStore().getDepotCacheDir();
            if (!dir.exists()) dir.mkdirs();
            File f = new File(dir, key + ".manifest");
            return ParcelFileDescriptor.open(f,
                    ParcelFileDescriptor.MODE_CREATE
                            | ParcelFileDescriptor.MODE_TRUNCATE
                            | ParcelFileDescriptor.MODE_WRITE_ONLY);
        }
        if ("fix_in".equals(kind)) {
            String appId = segs.get(1).replaceAll("[^0-9]", "");
            if (appId.isEmpty()) throw new FileNotFoundException("Invalid appId");
            File f = new File(ctx.getCacheDir(), "fix_in_" + appId + ".zip");
            return ParcelFileDescriptor.open(f,
                    ParcelFileDescriptor.MODE_CREATE
                            | ParcelFileDescriptor.MODE_TRUNCATE
                            | ParcelFileDescriptor.MODE_WRITE_ONLY);
        }
        if ("pack_in".equals(kind) && segs.size() >= 3) {
            String emuMode = segs.get(1).replaceAll("[^a-zA-Z0-9_-]", "");
            String fileName = new File(segs.get(2)).getName();
            String lower = fileName.toLowerCase();
            if (!lower.endsWith(".dll") && !lower.endsWith(".toml") && !lower.endsWith(".ini")) {
                throw new FileNotFoundException("Unsupported pack file: " + fileName);
            }
            File f = LuaToolsSteamEmu.packIncomingFile(ctx, emuMode, fileName);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            return ParcelFileDescriptor.open(f,
                    ParcelFileDescriptor.MODE_CREATE
                            | ParcelFileDescriptor.MODE_TRUNCATE
                            | ParcelFileDescriptor.MODE_WRITE_ONLY);
        }
        throw new FileNotFoundException("Unsupported path: " + uri);
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        Bundle res = new Bundle();
        if (ctx == null) {
            res.putBoolean("ok", false);
            return res;
        }
        LuaToolsJavaSteamHook hook = LuaToolsJavaSteamHook.get(ctx);
        LuaToolsStore store = hook.getStore();

        switch (method) {
            case "ping": {
                res.putBoolean("ok", true);
                res.putInt("bridge_version", BRIDGE_VERSION);
                res.putString("app_name", getAppDisplayName(ctx));
                res.putString("package_name", ctx.getPackageName());
                res.putBoolean("supports_auto_sync", true);
                return res;
            }
            case "list_prefixes": {
                res.putBoolean("ok", true);
                res.putStringArrayList("prefixes", new ArrayList<String>());
                return res;
            }
            case "list_lua_games": {
                ArrayList<Integer> ids = new ArrayList<>(store.listLuaAppIds());
                res.putBoolean("ok", true);
                res.putIntegerArrayList("app_ids", ids);
                return res;
            }
            case "add_steam_game": {
                int appId = extras != null ? extras.getInt("app_id", 0) : 0;
                String name = extras != null ? extras.getString("name", "") : "";
                String luaContent = extras != null ? extras.getString("lua_content", "") : "";
                if (appId <= 0 || luaContent == null || luaContent.isEmpty()) {
                    res.putBoolean("ok", false);
                    res.putString("error", "Missing app_id or lua_content");
                    return res;
                }
                try {
                    store.saveLuaContent(appId, luaContent);
                    Map<Integer, Long> manifests = store.getWindowsDepotManifests(appId);
                    int cached = 0;
                    for (Map.Entry<Integer, Long> e : manifests.entrySet()) {
                        if (store.getManifestFile(e.getKey(), e.getValue()) != null) cached++;
                    }
                    String resolvedName = (name != null && !name.isEmpty() && !name.startsWith("AppID "))
                            ? name : store.getGameName(appId);
                    onGameAdded(ctx, appId, resolvedName, store);
                    res.putBoolean("ok", true);
                    res.putInt("app_id", appId);
                    res.putInt("manifests_cached", cached);
                    res.putString("name", resolvedName);
                    res.putString("path", new File(store.getStPlugInDir(), appId + ".lua").getAbsolutePath());
                } catch (Exception e) {
                    res.putBoolean("ok", false);
                    res.putString("error", e.getMessage());
                }
                return res;
            }
            case "apply_fix": {
                int appId = extras != null ? extras.getInt("app_id", 0) : 0;
                String name = extras != null ? extras.getString("name", "") : "";
                String fixVersion = extras != null ? extras.getString("fix_version", "1.0.0") : "1.0.0";
                if (appId <= 0) {
                    res.putBoolean("ok", false);
                    res.putString("error", "Invalid app_id");
                    return res;
                }
                File zipFile = new File(ctx.getCacheDir(), "fix_in_" + appId + ".zip");
                if (!zipFile.isFile() || zipFile.length() == 0) {
                    res.putBoolean("ok", false);
                    res.putString("error", "No uploaded fix archive found");
                    return res;
                }
                try {
                    File gameDir = resolveGameInstallDir(ctx, appId, name);
                    if (!gameDir.exists()) gameDir.mkdirs();
                    Set<String> overrides = new LinkedHashSet<>();
                    List<String> extractedFiles = new ArrayList<>();
                    int extracted = extractFixZipWithBackup(zipFile, gameDir, overrides, extractedFiles);
                    zipFile.delete();
                    saveFixMarker(ctx, appId, gameDir, fixVersion, extractedFiles);
                    if (!overrides.isEmpty()) {
                        onFixDllOverrides(ctx, appId, overrides);
                    }
                    StringBuilder ov = new StringBuilder();
                    for (String s : overrides) {
                        if (ov.length() > 0) ov.append(',');
                        ov.append(s);
                    }
                    res.putBoolean("ok", true);
                    res.putInt("app_id", appId);
                    res.putInt("extracted_files", extracted);
                    res.putString("fix_version", fixVersion);
                    res.putString("game_dir", gameDir.getAbsolutePath());
                    res.putString("dll_overrides", ov.toString());
                } catch (Exception e) {
                    res.putBoolean("ok", false);
                    res.putString("error", e.getMessage());
                }
                return res;
            }
            case "remove_fix": {
                int appId = extras != null ? extras.getInt("app_id", 0) : 0;
                if (appId <= 0) {
                    res.putBoolean("ok", false);
                    res.putString("error", "Invalid app_id");
                    return res;
                }
                try {
                    int removed = removeFixWithRestore(ctx, appId);
                    res.putBoolean("ok", true);
                    res.putInt("app_id", appId);
                    res.putInt("removed_files", removed);
                } catch (Exception e) {
                    res.putBoolean("ok", false);
                    res.putString("error", e.getMessage());
                }
                return res;
            }
            case "fix_status": {
                try {
                    JSONObject installed = listInstalledFixes(ctx);
                    res.putBoolean("ok", true);
                    res.putString("installed_json", installed.toString());
                } catch (Exception e) {
                    res.putBoolean("ok", false);
                    res.putString("error", e.getMessage());
                }
                return res;
            }
            case "set_steam_emu": {
                String m = null;
                if (extras != null) {
                    m = extras.getString("mode");
                    if (m != null) LuaToolsSteamEmu.setMode(ctx, m);
                    if (extras.containsKey("real_steam")) {
                        LuaToolsSteamEmu.setUseRealSteamClient(ctx, extras.getBoolean("real_steam"));
                    }
                    if (extras.containsKey("autoclose_wine")) {
                        LuaToolsSteamEmu.setAutoCloseEnabled(ctx, extras.getBoolean("autoclose_wine"));
                    }
                }
                if (m != null) {
                    if (LuaToolsSteamEmu.MODE_OFF.equals(LuaToolsSteamEmu.mode(ctx))) {
                        LuaToolsSteamEmu.detachSteamClient(ctx, createSteamEmuHostAdapter());
                    } else {
                        LuaToolsSteamEmu.attachSteamClient(ctx, createSteamEmuHostAdapter());
                    }
                }
                res.putBoolean("ok", true);
                res.putString("result_json", LuaToolsSteamEmu.status(ctx).toString());
                return res;
            }
            case "steam_emu_attach": {
                int appId = extras != null ? extras.getInt("app_id", 0) : 0;
                LuaToolsSteamEmu.HostAdapter host = createSteamEmuHostAdapter();
                JSONObject out = new JSONObject();
                try {
                    out.put("steam_client", LuaToolsSteamEmu.attachSteamClient(ctx, host));
                    if (appId > 0) {
                        out.put("game", LuaToolsSteamEmu.attachGameRuntime(ctx, appId, resolveGameInstallDir(ctx, appId, "")));
                    }
                } catch (Throwable ignored) {}
                res.putBoolean("ok", true);
                res.putString("result_json", out.toString());
                return res;
            }
            case "steam_emu_detach": {
                int appId = extras != null ? extras.getInt("app_id", 0) : 0;
                JSONObject out = (appId > 0)
                        ? LuaToolsSteamEmu.detachGame(ctx, appId)
                        : LuaToolsSteamEmu.detachSteamClient(ctx, createSteamEmuHostAdapter());
                res.putBoolean("ok", true);
                res.putString("result_json", out.toString());
                return res;
            }
            case "steam_emu_status":
            case "bridge_status": {
                res.putBoolean("ok", true);
                res.putInt("bridge_version", BRIDGE_VERSION);
                res.putString("result_json", LuaToolsSteamEmu.status(ctx).toString());
                return res;
            }
            default:
                res.putBoolean("ok", false);
                res.putString("error", "Unknown method: " + method);
                return res;
        }
    }

    private static File fixMarkerDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "luatools/fixes");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static void saveFixMarker(Context ctx, int appId, File gameDir, String version, List<String> files) throws Exception {
        JSONObject marker = new JSONObject();
        marker.put("app_id", appId);
        marker.put("game_dir", gameDir.getAbsolutePath());
        marker.put("version", (version == null || version.isEmpty()) ? "1.0.0" : version);
        marker.put("ts", System.currentTimeMillis());
        org.json.JSONArray arr = new org.json.JSONArray();
        for (String rel : files) arr.put(rel);
        marker.put("files", arr);
        LuaToolsStore.writeAllBytes(new File(fixMarkerDir(ctx), appId + ".json"), marker.toString().getBytes("UTF-8"));
    }

    private static JSONObject listInstalledFixes(Context ctx) {
        JSONObject out = new JSONObject();
        File[] fs = fixMarkerDir(ctx).listFiles();
        if (fs == null) return out;
        for (File f : fs) {
            if (!f.getName().endsWith(".json")) continue;
            try {
                JSONObject m = new JSONObject(new String(LuaToolsStore.readAllBytes(f), "UTF-8"));
                int appId = m.optInt("app_id", 0);
                File gameDir = new File(m.optString("game_dir", ""));
                org.json.JSONArray files = m.optJSONArray("files");
                boolean present = gameDir.isDirectory() && files != null && files.length() > 0;
                if (present) {
                    for (int i = 0; i < files.length(); i++) {
                        if (!new File(gameDir, files.getString(i)).isFile()) {
                            present = false;
                            break;
                        }
                    }
                }
                if (present && appId > 0) {
                    out.put(String.valueOf(appId), m.optString("version", "1.0.0"));
                } else {
                    f.delete();
                }
            } catch (Throwable ignored) {}
        }
        return out;
    }

    private static int removeFixWithRestore(Context ctx, int appId) throws Exception {
        File mf = new File(fixMarkerDir(ctx), appId + ".json");
        if (!mf.isFile()) return 0;
        JSONObject m = new JSONObject(new String(LuaToolsStore.readAllBytes(mf), "UTF-8"));
        File gameDir = new File(m.optString("game_dir", ""));
        File backupDir = new File(gameDir, ".luatools_backup");
        org.json.JSONArray files = m.optJSONArray("files");
        int removed = 0;
        if (files != null && gameDir.isDirectory()) {
            for (int i = 0; i < files.length(); i++) {
                String rel = files.getString(i);
                File target = new File(gameDir, rel);
                if (target.isFile() && target.delete()) removed++;
                File bak = new File(backupDir, rel);
                if (bak.isFile()) {
                    LuaToolsStore.writeAllBytes(target, LuaToolsStore.readAllBytes(bak));
                    bak.delete();
                }
            }
        }
        mf.delete();
        return removed;
    }

    private static int extractFixZipWithBackup(File zipFile, File gameDir, Set<String> proxiesOut, List<String> extractedRelOut) throws Exception {
        File backupDir = new File(gameDir, ".luatools_backup");
        String canonicalGame = gameDir.getCanonicalPath() + File.separator;
        int count = 0;
        byte[] buf = new byte[8192];
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.isEmpty() || name.startsWith("/") || name.contains("..")) continue;
                File outFile = new File(gameDir, name);
                if (!outFile.getCanonicalPath().startsWith(canonicalGame)) continue;
                if (entry.isDirectory()) {
                    outFile.mkdirs();
                } else {
                    File parent = outFile.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    if (outFile.isFile()) {
                        File bak = new File(backupDir, name);
                        if (!bak.exists()) {
                            LuaToolsStore.writeAllBytes(bak, LuaToolsStore.readAllBytes(outFile));
                        }
                    }
                    FileOutputStream fos = new FileOutputStream(outFile);
                    try {
                        int r;
                        while ((r = zis.read(buf)) != -1) fos.write(buf, 0, r);
                    } finally {
                        fos.close();
                    }
                    extractedRelOut.add(name);
                    String base = outFile.getName().toLowerCase();
                    if (base.endsWith(".dll")) {
                        String stem = base.substring(0, base.length() - 4);
                        if (KNOWN_PROXY_DLLS.contains(stem)) proxiesOut.add(stem);
                    }
                    count++;
                }
                zis.closeEntry();
            }
        } finally {
            zis.close();
        }
        return count;
    }

    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { return null; }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { return null; }
    @Override public int delete(Uri u, String s, String[] a) { return 0; }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { return 0; }
}
