package com.luatools.javasteam;

import android.content.Context;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads LuaTools stplug-in/<appId>.lua files, depotcache/<depotId>_<manifestId>.manifest files,
 * and caches real Steam PICS metadata from api.steamcmd.net.
 *
 * Zero external dependencies — compiles against android.jar only.
 */
public final class LuaToolsStore {

    private static final Pattern ADDAPPID_KEY =
            Pattern.compile("addappid\\(\\s*(\\d+)\\s*,\\s*\\d+\\s*,\\s*\"([0-9a-fA-F]{64})\"");
    private static final Pattern SET_MANIFEST =
            Pattern.compile("setManifestid\\(\\s*(\\d+)\\s*,\\s*\"(\\d+)\"");
    private static final Pattern HEADER_NAME =
            Pattern.compile("^--\\s*(\\d+)\\s*-\\s*(.+)$", Pattern.MULTILINE);

    private final File stPlugInDir;
    private final File depotCacheDir;
    private final File picsCacheDir;

    public LuaToolsStore(Context ctx) {
        this(
                new File(ctx.getFilesDir(), "Steam/config/stplug-in"),
                new File(ctx.getFilesDir(), "Steam/depotcache"),
                new File(ctx.getCacheDir(), "luatools_pics")
        );
    }

    public LuaToolsStore(File stPlugInDir, File depotCacheDir, File picsCacheDir) {
        this.stPlugInDir = stPlugInDir;
        this.depotCacheDir = depotCacheDir;
        this.picsCacheDir = picsCacheDir;
        if (!stPlugInDir.exists()) stPlugInDir.mkdirs();
        if (!depotCacheDir.exists()) depotCacheDir.mkdirs();
        if (!picsCacheDir.exists()) picsCacheDir.mkdirs();
    }

    public File getStPlugInDir() { return stPlugInDir; }
    public File getDepotCacheDir() { return depotCacheDir; }

    public boolean isLuaGame(int appId) {
        if (appId <= 0) return false;
        File f = new File(stPlugInDir, appId + ".lua");
        return f.isFile() && f.length() > 0;
    }

    public List<Integer> listLuaAppIds() {
        File[] files = stPlugInDir.listFiles();
        if (files == null) return Collections.emptyList();
        List<Integer> out = new ArrayList<>();
        for (File f : files) {
            if (f.isFile() && f.getName().endsWith(".lua")) {
                String stem = f.getName().substring(0, f.getName().length() - 4);
                try {
                    int id = Integer.parseInt(stem);
                    if (id > 0) out.add(id);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Collections.sort(out);
        return out;
    }

    public String readLuaContent(int appId) {
        File f = new File(stPlugInDir, appId + ".lua");
        if (!f.isFile()) return null;
        try {
            return new String(readAllBytes(f), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }

    public void saveLuaContent(int appId, String luaContent) throws Exception {
        if (!stPlugInDir.exists()) stPlugInDir.mkdirs();
        writeAllBytes(new File(stPlugInDir, appId + ".lua"), luaContent.getBytes("UTF-8"));
    }

    public String getGameName(int appId) {
        String lua = readLuaContent(appId);
        if (lua != null) {
            Matcher m = HEADER_NAME.matcher(lua);
            if (m.find() && String.valueOf(appId).equals(m.group(1))) {
                String n = m.group(2).trim();
                if (!n.isEmpty()) return n;
            }
        }
        JSONObject pics = getPicsInfo(appId);
        if (pics != null) {
            JSONObject common = pics.optJSONObject("common");
            String n = common != null ? common.optString("name", "") : "";
            if (!n.isEmpty()) return n;
        }
        return "AppID " + appId;
    }

    /** Returns map of depotId -> 32-byte decryption key across all installed .lua files. */
    public Map<Integer, byte[]> getAllDepotKeys() {
        Map<Integer, byte[]> keys = new HashMap<>();
        for (int appId : listLuaAppIds()) {
            String lua = readLuaContent(appId);
            if (lua == null) continue;
            Matcher m = ADDAPPID_KEY.matcher(lua);
            while (m.find()) {
                try {
                    int depotId = Integer.parseInt(m.group(1));
                    keys.put(depotId, hexToBytes(m.group(2)));
                } catch (Exception ignored) {
                }
            }
        }
        return keys;
    }

    /** Looks up a single depot decryption key (32 bytes) from installed .lua files, or null. */
    public byte[] getDepotKey(int depotId) {
        for (int appId : listLuaAppIds()) {
            String lua = readLuaContent(appId);
            if (lua == null) continue;
            Matcher m = ADDAPPID_KEY.matcher(lua);
            while (m.find()) {
                if (m.group(1).equals(String.valueOf(depotId))) {
                    return hexToBytes(m.group(2));
                }
            }
        }
        return null;
    }

    /** Returns ordered map of Windows depotId -> manifestId for an app. */
    public Map<Integer, Long> getWindowsDepotManifests(int appId) {
        Map<Integer, Long> result = new LinkedHashMap<>();
        String lua = readLuaContent(appId);
        if (lua == null) return result;
        Matcher m = SET_MANIFEST.matcher(lua);
        while (m.find()) {
            try {
                int depotId = Integer.parseInt(m.group(1));
                if (isNonWindowsDepot(lua, depotId)) continue;
                long manifestId = Long.parseLong(m.group(2));
                result.put(depotId, manifestId);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    public static boolean isNonWindowsDepot(String lua, int depotId) {
        Matcher m = Pattern.compile("addappid\\(\\s*" + depotId + "\\b[^\\n]*?--\\s*\\(([^)]*)\\)").matcher(lua);
        if (m.find()) {
            String tag = m.group(1).toLowerCase();
            return !tag.contains("windows") && (tag.contains("linux") || tag.contains("mac"));
        }
        return false;
    }

    /** Returns the cached raw .manifest file for (depotId, manifestId), or null if absent. */
    public File getManifestFile(int depotId, long manifestId) {
        File f = new File(depotCacheDir, depotId + "_" + manifestId + ".manifest");
        if (f.isFile() && f.length() > 0) return f;
        File ext = new File("/sdcard/Games/LuaTools/depotcache/" + depotId + "_" + manifestId + ".manifest");
        if (ext.isFile() && ext.length() > 0) return ext;
        return null;
    }

    /**
     * Fetches (and caches) real Steam PICS metadata from api.steamcmd.net.
     * Provides real installdir, launch executables, and depot OS config.
     */
    public JSONObject getPicsInfo(int appId) {
        File cacheFile = new File(picsCacheDir, "pics_" + appId + ".json");
        if (cacheFile.isFile() && cacheFile.length() > 0) {
            try {
                return new JSONObject(new String(readAllBytes(cacheFile), "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL("https://api.steamcmd.net/v1/info/" + appId).openConnection();
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(12000);
            if (conn.getResponseCode() != 200) return null;
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            JSONObject root = new JSONObject(bos.toString("UTF-8"));
            JSONObject data = root.optJSONObject("data");
            JSONObject app = data != null ? data.optJSONObject(String.valueOf(appId)) : null;
            if (app != null) {
                writeAllBytes(cacheFile, app.toString().getBytes("UTF-8"));
            }
            return app;
        } catch (Throwable t) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    public static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }

    public static byte[] readAllBytes(File f) throws Exception {
        FileInputStream fis = new FileInputStream(f);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.max(256, f.length()));
            byte[] buf = new byte[8192];
            int r;
            while ((r = fis.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        } finally {
            fis.close();
        }
    }

    public static void writeAllBytes(File f, byte[] data) throws Exception {
        File parent = f.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();
        FileOutputStream fos = new FileOutputStream(f);
        try {
            fos.write(data);
        } finally {
            fos.close();
        }
    }
}
