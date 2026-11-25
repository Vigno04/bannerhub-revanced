package com.luatools.javasteam;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Portable BetterSteamTools / OpenSteamTools & Steamworks runtime manager for ANY
 * Android Wine/Steam emulator (BannerHub, GameNative, Winlator, etc.).
 *
 * Zero host-app dependencies: compiles against android.jar only.
 * Host-specific details (Steam client directory, start script overrides, container lookup)
 * are provided via {@link HostAdapter} or {@link LuaToolsBridgeProvider} overrides.
 */
public final class LuaToolsSteamEmu {

    private LuaToolsSteamEmu() {}

    public static final String PREFS = "luatools_steam_emu";
    private static final String KEY_MODE = "mode";
    private static final String KEY_REAL_STEAM = "real_steam";
    private static final String KEY_AUTOCLOSE = "autoclose_wine";

    public static final String MODE_OFF = "off";
    public static final String MODE_BST = "bst";
    public static final String MODE_OST = "ost";
    public static final String DEFAULT_MODE = MODE_OFF;

    public static final String[] PACK_FILES = {"OpenSteamTool.dll", "dwmapi.dll", "xinput1_4.dll"};
    public static final String[] OVERRIDE_STEMS = {"dwmapi", "xinput1_4"};
    public static final String BACKUP_SUFFIX = ".luatools_backup";

    /** Optional host-specific hooks implemented by the target emulator adapter. */
    public interface HostAdapter {
        File resolveSteamClientDir(Context ctx);
        String resolveSteamLuaPathWin(Context ctx);
        File resolveGameInstallDir(Context ctx, int appId);
        void onSteamClientAttached(Context ctx, File steamClientDir, String mode);
        void onSteamClientDetached(Context ctx, File steamClientDir);
    }

    // ---------------------------------------------------------------- prefs

    public static String mode(Context ctx) {
        String m = prefs(ctx).getString(KEY_MODE, null);
        return (m == null || m.isEmpty()) ? DEFAULT_MODE : m.toLowerCase();
    }

    public static void setMode(Context ctx, String mode) {
        String m = (mode == null) ? MODE_OFF : mode.trim().toLowerCase();
        if (!m.equals(MODE_OFF) && !m.equals(MODE_BST) && !m.equals(MODE_OST)) m = MODE_OFF;
        prefs(ctx).edit().putString(KEY_MODE, m).commit();
    }

    public static boolean useRealSteamClient(Context ctx) {
        return prefs(ctx).getBoolean(KEY_REAL_STEAM, false);
    }

    public static void setUseRealSteamClient(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_REAL_STEAM, on).commit();
    }

    public static boolean autoCloseEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_AUTOCLOSE, true);
    }

    public static void setAutoCloseEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(KEY_AUTOCLOSE, on).commit();
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- paths

    public static File stateRootDir(Context ctx) {
        File f = new File(ctx.getFilesDir(), "luatools/steam");
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File packIncomingDir(Context ctx, String mode) {
        File f = new File(stateRootDir(ctx), "incoming/" + sanitizeMode(mode));
        if (!f.exists()) f.mkdirs();
        return f;
    }

    public static File packIncomingFile(Context ctx, String mode, String fileName) {
        String safeName = new File(fileName).getName();
        return new File(packIncomingDir(ctx, mode), safeName);
    }

    public static File packInstallDir(Context ctx, String mode) {
        File f = new File(stateRootDir(ctx), "packs/" + sanitizeMode(mode));
        if (!f.exists()) f.mkdirs();
        return f;
    }

    private static File steamClientMarker(Context ctx) {
        File dir = new File(stateRootDir(ctx), "state");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "steam_client.json");
    }

    private static File gameMarker(Context ctx, int appId) {
        File dir = new File(stateRootDir(ctx), "state");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, appId + ".json");
    }

    private static String sanitizeMode(String mode) {
        if (MODE_OST.equalsIgnoreCase(mode)) return MODE_OST;
        return MODE_BST;
    }

    private static File[] packSources(Context ctx, String mode) {
        String m = sanitizeMode(mode);
        return new File[]{
                packIncomingDir(ctx, m),
                new File("/sdcard/Games/LuaTools/steam/" + m),
                new File(ctx.getExternalFilesDir(null), "Games/LuaTools/steam/" + m)
        };
    }

    // ---------------------------------------------------------------- pack

    public static File ensurePack(Context ctx, String mode, JSONObject diag) {
        File target = packInstallDir(ctx, mode);
        File src = null;
        for (File c : packSources(ctx, mode)) {
            if (c.isDirectory() && new File(c, PACK_FILES[0]).isFile()) {
                src = c;
                break;
            }
        }
        if (src == null) {
            try {
                diag.put("pack_error", "pack not found; push via Companion or place in /sdcard/Games/LuaTools/steam/" + mode + "/");
            } catch (Throwable ignored) {}
            return null;
        }
        try {
            diag.put("pack_source", src.getAbsolutePath());
            for (String name : PACK_FILES) {
                File s = new File(src, name);
                if (!s.isFile()) {
                    diag.put("pack_missing", name);
                    return null;
                }
                File d = new File(target, name);
                if (!d.isFile() || d.length() != s.length()) {
                    copy(s, d);
                }
            }
            diag.put("pack_dir", target.getAbsolutePath());
            return target;
        } catch (Throwable t) {
            try { diag.put("pack_error", String.valueOf(t.getMessage())); } catch (Throwable ignored) {}
            return null;
        }
    }

    public static boolean isSteamClientAttached(Context ctx) {
        return steamClientMarker(ctx).isFile();
    }

    // ---------------------------------------------------------------- attach / detach Steam client

    /**
     * Attaches BetterSteamTools / OpenSteamTools strictly to the Steam client root directory
     * (never system32/syswow64 or game directories) and writes opensteamtool.toml.
     */
    public static JSONObject attachSteamClient(Context ctx, HostAdapter host) {
        JSONObject out = new JSONObject();
        try {
            String mode = mode(ctx);
            out.put("mode", mode);
            if (MODE_OFF.equals(mode)) {
                out.put("ok", true);
                out.put("skipped", "mode_off");
                return out;
            }
            File steamClientDir = host != null ? host.resolveSteamClientDir(ctx) : null;
            if (steamClientDir == null || !steamClientDir.isDirectory()) {
                out.put("ok", false);
                out.put("error", "steam_client_dir_not_found");
                return out;
            }
            JSONObject diag = new JSONObject();
            File packDir = ensurePack(ctx, mode, diag);
            out.put("pack", diag);
            if (packDir == null) {
                out.put("ok", false);
                out.put("error", diag.optString("pack_error", "pack_unavailable"));
                return out;
            }

            List<String> created = new ArrayList<>();
            JSONObject backups = new JSONObject();
            JSONArray actions = new JSONArray();

            for (String name : PACK_FILES) {
                File src = new File(packDir, name);
                File dst = new File(steamClientDir, name);
                if (placeWithBackup(src, dst, created, backups)) {
                    actions.put("placed:" + name);
                }
            }

            String luaPathWin = host.resolveSteamLuaPathWin(ctx);
            if (luaPathWin == null || luaPathWin.isEmpty()) {
                luaPathWin = "config/stplug-in";
            }
            File toml = new File(steamClientDir, "opensteamtool.toml");
            backupIfForeign(toml, created, backups);
            String escapedPath = luaPathWin.replace("\\", "\\\\");
            String tomlBody = "[lua]\npaths = [\"" + escapedPath + "\"]\n\n[cloud]\nenabled = false\n";
            writeAll(toml, tomlBody.getBytes("UTF-8"));
            if (!created.contains(toml.getAbsolutePath())) created.add(toml.getAbsolutePath());
            actions.put("wrote:opensteamtool.toml");

            host.onSteamClientAttached(ctx, steamClientDir, mode);

            JSONObject marker = new JSONObject();
            marker.put("mode", mode);
            marker.put("steam_client_dir", steamClientDir.getAbsolutePath());
            marker.put("ts", System.currentTimeMillis());
            marker.put("created", new JSONArray(created));
            marker.put("backups", backups);
            writeAll(steamClientMarker(ctx), marker.toString().getBytes("UTF-8"));

            out.put("ok", true);
            out.put("steam_client_dir", steamClientDir.getAbsolutePath());
            out.put("actions", actions);
        } catch (Throwable t) {
            try { out.put("ok", false); out.put("error", String.valueOf(t.getMessage())); } catch (Throwable ignored) {}
        }
        return out;
    }

    public static JSONObject detachSteamClient(Context ctx, HostAdapter host) {
        JSONObject out = new JSONObject();
        try {
            File mf = steamClientMarker(ctx);
            if (!mf.isFile()) {
                out.put("ok", true);
                out.put("skipped", "not_attached");
                return out;
            }
            JSONObject marker = new JSONObject(new String(readAll(mf), "UTF-8"));
            File steamClientDir = new File(marker.optString("steam_client_dir", ""));
            restoreMarkerFiles(marker);
            if (host != null && steamClientDir.isDirectory()) {
                host.onSteamClientDetached(ctx, steamClientDir);
            }
            mf.delete();
            out.put("ok", true);
        } catch (Throwable t) {
            try { out.put("ok", false); out.put("error", String.valueOf(t.getMessage())); } catch (Throwable ignored) {}
        }
        return out;
    }

    // ---------------------------------------------------------------- game runtime (isolated helper)

    /**
     * Ensures a game directory has its own steam_api64.dll next to the executable (for Unity/Unreal
     * games that bury it in Plugins/x86_64 or Engine/Binaries/ThirdParty/Steamworks) and
     * steam_appid.txt. Never places BST/OST shims in game directories or system32.
     */
    public static JSONObject attachGameRuntime(Context ctx, int appId, File gameDir) {
        JSONObject out = new JSONObject();
        try {
            out.put("app_id", appId);
            if (gameDir == null || !gameDir.isDirectory()) {
                out.put("ok", false);
                out.put("error", "game_dir_not_found");
                return out;
            }
            List<String> created = new ArrayList<>();
            JSONArray actions = new JSONArray();

            File runtime = ensureSteamworksRuntime(gameDir);
            if (runtime != null) {
                created.add(runtime.getAbsolutePath());
                actions.put("steamworks_runtime:" + runtime.getName());
            }

            File appIdFile = new File(gameDir, "steam_appid.txt");
            if (!appIdFile.isFile()) {
                writeAll(appIdFile, (appId + "\n").getBytes("UTF-8"));
                created.add(appIdFile.getAbsolutePath());
                actions.put("steam_appid_txt");
            }

            JSONObject marker = new JSONObject();
            marker.put("app_id", appId);
            marker.put("game_dir", gameDir.getAbsolutePath());
            marker.put("ts", System.currentTimeMillis());
            marker.put("created", new JSONArray(created));
            marker.put("backups", new JSONObject());
            writeAll(gameMarker(ctx, appId), marker.toString().getBytes("UTF-8"));

            out.put("ok", true);
            out.put("game_dir", gameDir.getAbsolutePath());
            out.put("actions", actions);
        } catch (Throwable t) {
            try { out.put("ok", false); out.put("error", String.valueOf(t.getMessage())); } catch (Throwable ignored) {}
        }
        return out;
    }

    public static JSONObject detachGame(Context ctx, int appId) {
        JSONObject out = new JSONObject();
        try {
            out.put("app_id", appId);
            File mf = gameMarker(ctx, appId);
            if (!mf.isFile()) {
                out.put("ok", true);
                out.put("skipped", "not_attached");
                return out;
            }
            JSONObject marker = new JSONObject(new String(readAll(mf), "UTF-8"));
            restoreMarkerFiles(marker);
            mf.delete();
            out.put("ok", true);
        } catch (Throwable t) {
            try { out.put("ok", false); out.put("error", String.valueOf(t.getMessage())); } catch (Throwable ignored) {}
        }
        return out;
    }

    public static JSONObject status(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            String mode = mode(ctx);
            out.put("mode", mode);
            out.put("real_steam", useRealSteamClient(ctx));
            out.put("autoclose_wine", autoCloseEnabled(ctx));
            out.put("steam_client_attached", isSteamClientAttached(ctx));
            JSONObject diag = new JSONObject();
            File packDir = MODE_OFF.equals(mode) ? null : ensurePack(ctx, mode, diag);
            out.put("pack", diag);
            out.put("pack_ready", packDir != null);

            JSONArray attached = new JSONArray();
            File[] fs = new File(stateRootDir(ctx), "state").listFiles();
            if (fs != null) {
                for (File f : fs) {
                    String fn = f.getName();
                    if (!fn.endsWith(".json") || "steam_client.json".equals(fn)) continue;
                    try {
                        JSONObject m = new JSONObject(new String(readAll(f), "UTF-8"));
                        int appId = m.optInt("app_id");
                        File gdir = new File(m.optString("game_dir"));
                        JSONObject s = new JSONObject();
                        s.put("app_id", appId);
                        s.put("game_dir", m.optString("game_dir"));
                        s.put("attached_at", m.optLong("ts"));
                        s.put("steamworks_runtime_present", new File(gdir, "steam_api64.dll").isFile());
                        attached.put(s);
                    } catch (Throwable ignored) {}
                }
            }
            out.put("attached", attached);
        } catch (Throwable ignored) {}
        return out;
    }

    // ---------------------------------------------------------------- helpers

    public static File ensureSteamworksRuntime(File gameDir) throws Exception {
        File dst = new File(gameDir, "steam_api64.dll");
        if (dst.isFile()) return null;
        File src = findFile(gameDir, "steam_api64.dll", 0, 8);
        if (src == null || gameDir.equals(src.getParentFile())) return null;
        copy(src, dst);
        return dst;
    }

    private static File findFile(File root, String name, int depth, int maxDepth) {
        if (depth > maxDepth || root == null || !root.isDirectory()) return null;
        File[] fs = root.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isFile() && f.getName().equalsIgnoreCase(name)) return f;
        }
        for (File f : fs) {
            if (f.isDirectory()) {
                File hit = findFile(f, name, depth + 1, maxDepth);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    private static boolean placeWithBackup(File src, File dst, List<String> created, JSONObject backups) throws Exception {
        if (!src.isFile()) return false;
        if (dst.isFile() && dst.length() == src.length()) return false;
        backupIfForeign(dst, created, backups);
        copy(src, dst);
        if (!created.contains(dst.getAbsolutePath())) created.add(dst.getAbsolutePath());
        return true;
    }

    private static void backupIfForeign(File dst, List<String> created, JSONObject backups) throws Exception {
        if (!dst.isFile()) return;
        File b = new File(dst.getAbsolutePath() + BACKUP_SUFFIX);
        if (!b.isFile()) {
            if (dst.renameTo(b)) {
                backups.put(dst.getAbsolutePath(), b.getAbsolutePath());
                return;
            }
        }
        dst.delete();
    }

    private static void restoreMarkerFiles(JSONObject marker) throws Exception {
        JSONArray created = marker.optJSONArray("created");
        if (created != null) {
            for (int i = 0; i < created.length(); i++) {
                File f = new File(created.getString(i));
                if (f.isFile()) f.delete();
            }
        }
        JSONObject backups = marker.optJSONObject("backups");
        if (backups != null) {
            Iterator<String> it = backups.keys();
            while (it.hasNext()) {
                String dst = it.next();
                File backup = new File(backups.getString(dst));
                File dest = new File(dst);
                if (dest.isFile()) dest.delete();
                if (backup.isFile()) backup.renameTo(dest);
            }
        }
    }

    public static void copy(File src, File dst) throws Exception {
        File parent = dst.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileInputStream in = new FileInputStream(src);
        FileOutputStream out = new FileOutputStream(dst);
        try {
            byte[] buf = new byte[16384];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
            out.flush();
        } finally {
            in.close();
            out.close();
        }
    }

    public static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        } finally {
            in.close();
        }
    }

    public static void writeAll(File f, byte[] data) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
            out.flush();
        } finally {
            out.close();
        }
    }
}
