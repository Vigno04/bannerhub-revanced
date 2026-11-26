package com.luatools.javasteam;

import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Universal, sandbox-safe CloudRedirect sync helper for ANY Android Steam emulator
 * (GameNative, Pluvia, BannerHub, GameHub, Winlator, etc.).
 *
 * Transfers save files over ContentProvider ParcelFileDescriptor streams using
 * Steam AutoCloud canonical (root_token, relative_path) keys for 100% PC/Android parity.
 */
public final class LuaToolsCloudSync {

    private static final String TAG = "LuaToolsCloudSync";
    public static final String COMPANION_PKG = "com.luatools.companion";
    public static final Uri COMPANION_BRIDGE_URI =
            Uri.parse("content://com.luatools.companion.bridge");

    private LuaToolsCloudSync() {}

    public static final class CloudSaveTarget {
        public final File rootDir;
        public final String rootToken;
        public final String subPath;

        public CloudSaveTarget(File rootDir, String rootToken, String subPath) {
            this.rootDir = rootDir;
            this.rootToken = rootToken == null ? "" : rootToken;
            this.subPath = subPath == null ? "" : subPath;
        }

        public File getTargetDir() {
            return subPath.isEmpty() ? rootDir : new File(rootDir, subPath);
        }
    }

    public static boolean isCompanionInstalled(Context ctx) {
        if (ctx == null) return false;
        try {
            ctx.getPackageManager().getPackageInfo(COMPANION_PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolves the game's canonical AutoCloud root directory, Steam root token (e.g. {@code %WinMyDocuments%}),
     * and relative sub-path (e.g. {@code My Games/<Game>/Steam/<steamId64>}), falling back to Goldberg's
     * {@code AppData/Roaming/GSE Saves/<appId>/remote} when no Windows AutoCloud path is declared.
     */
    public static CloudSaveTarget resolveCloudSaveTarget(
            Context ctx,
            int appId,
            File winePrefix,
            String userName,
            String steamId64
    ) {
        String user = (userName == null || userName.isEmpty()) ? "steamuser" : userName;
        File fallback = new File(winePrefix,
                "drive_c/users/" + user + "/AppData/Roaming/GSE Saves/" + appId + "/remote");
        if (ctx == null || winePrefix == null) {
            return new CloudSaveTarget(fallback, "%SteamUserBaseStorage%", "");
        }

        try {
            org.json.JSONObject pics = LuaToolsJavaSteamHook.get(ctx).getStore().getPicsInfo(appId);
            org.json.JSONObject savefiles = pics != null
                    ? pics.optJSONObject("ufs") != null
                        ? pics.optJSONObject("ufs").optJSONObject("savefiles")
                        : null
                    : null;
            if (savefiles == null) {
                return new CloudSaveTarget(fallback, "%SteamUserBaseStorage%", "");
            }

            java.util.Iterator<String> keys = savefiles.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                org.json.JSONObject entry = savefiles.optJSONObject(k);
                if (entry == null) continue;

                // Skip non-Windows entries if platforms is explicitly specified
                org.json.JSONObject platforms = entry.optJSONObject("platforms");
                if (platforms != null && platforms.length() > 0) {
                    boolean hasWin = false;
                    java.util.Iterator<String> pk = platforms.keys();
                    while (pk.hasNext()) {
                        String pv = platforms.optString(pk.next(), "").toLowerCase();
                        if (pv.contains("win") || pv.equals("all")) {
                            hasWin = true;
                            break;
                        }
                    }
                    if (!hasWin) continue;
                }

                String root = entry.optString("root", "");
                String relPath = entry.optString("path", "").replace('\\', '/');
                File baseDir;
                String rootToken;
                if (root.equalsIgnoreCase("WinMyDocuments") || root.equalsIgnoreCase("SteamAutoSaveRoot")) {
                    baseDir = new File(winePrefix, "drive_c/users/" + user + "/Documents");
                    rootToken = "%WinMyDocuments%";
                } else if (root.equalsIgnoreCase("WinAppDataRoaming")) {
                    baseDir = new File(winePrefix, "drive_c/users/" + user + "/AppData/Roaming");
                    rootToken = "%WinAppDataRoaming%";
                } else if (root.equalsIgnoreCase("WinAppDataLocal")) {
                    baseDir = new File(winePrefix, "drive_c/users/" + user + "/AppData/Local");
                    rootToken = "%WinAppDataLocal%";
                } else if (root.equalsIgnoreCase("WinAppDataLocalLow")) {
                    baseDir = new File(winePrefix, "drive_c/users/" + user + "/AppData/LocalLow");
                    rootToken = "%WinAppDataLocalLow%";
                } else if (root.equalsIgnoreCase("WinSavedGames")) {
                    baseDir = new File(winePrefix, "drive_c/users/" + user + "/Saved Games");
                    rootToken = "%WinSavedGames%";
                } else {
                    continue;
                }

                // Expand {64BitSteamID} using provided ID or auto-detected 7656119... folder
                if (relPath.contains("{64BitSteamID}")) {
                    int tokenIdx = relPath.indexOf("{64BitSteamID}");
                    String prefixRel = relPath.substring(0, tokenIdx).replaceAll("/+$", "");
                    String suffixRel = relPath.substring(tokenIdx + "{64BitSteamID}".length()).replaceAll("^/+", "");
                    String resolvedId = (steamId64 != null && !steamId64.isEmpty() && !steamId64.equals("0"))
                            ? steamId64
                            : detectSteamId64Dir(new File(baseDir, prefixRel));
                    if (resolvedId != null && !resolvedId.isEmpty()) {
                        relPath = (prefixRel.isEmpty() ? "" : prefixRel + "/")
                                + resolvedId
                                + (suffixRel.isEmpty() ? "" : "/" + suffixRel);
                    } else {
                        relPath = prefixRel;
                    }
                }
                relPath = relPath.replaceAll("^/+|/+$", "");
                Log.i(TAG, "Resolved cloud save target for appId=" + appId
                        + ": root=" + rootToken + " subPath=" + relPath);
                return new CloudSaveTarget(baseDir, rootToken, relPath);
            }
        } catch (Throwable t) {
            Log.w(TAG, "resolveCloudSaveTarget failed for appId=" + appId + ": " + t.getMessage());
        }
        return new CloudSaveTarget(fallback, "%SteamUserBaseStorage%", "");
    }

    public static File resolveCloudSaveDir(
            Context ctx,
            int appId,
            File winePrefix,
            String userName,
            String steamId64
    ) {
        return resolveCloudSaveTarget(ctx, appId, winePrefix, userName, steamId64).getTargetDir();
    }

    private static String detectSteamId64Dir(File parentDir) {
        if (parentDir == null || !parentDir.isDirectory()) return null;
        File[] children = parentDir.listFiles();
        if (children == null) return null;
        for (File c : children) {
            if (c.isDirectory() && c.getName().matches("7656119\\d{10}")) {
                return c.getName();
            }
        }
        return null;
    }

    public static boolean onPreLaunch(Context ctx, int appId, File saveRemoteDir) {
        return onPreLaunch(ctx, appId, new CloudSaveTarget(saveRemoteDir, "", ""));
    }

    /**
     * Call before launching a LuaTools game. Pulls the latest saves from Google Drive
     * via the LuaTools Companion App and unpacks them into {@code target.rootDir}.
     * Never overwrites local saves if offline or if a sync conflict is detected.
     */
    public static boolean onPreLaunch(Context ctx, int appId, CloudSaveTarget target) {
        if (ctx == null || appId <= 0 || target == null || target.rootDir == null
                || !isCompanionInstalled(ctx)) {
            return false;
        }
        try {
            Bundle args = new Bundle();
            args.putInt("app_id", appId);
            args.putString("source_package", ctx.getPackageName());
            args.putString("root_token", target.rootToken);
            args.putString("sub_path", target.subPath);

            Bundle res = ctx.getContentResolver().call(
                    COMPANION_BRIDGE_URI, "sync_pre_launch", String.valueOf(appId), args);
            if (res == null || !res.getBoolean("ok", false)) return false;
            if (res.getBoolean("has_conflict", false) || res.getBoolean("offline", false)) {
                Log.w(TAG, "onPreLaunch skipped unpack for appId=" + appId
                        + " conflict=" + res.getBoolean("has_conflict", false)
                        + " offline=" + res.getBoolean("offline", false));
                return false;
            }

            String zipUriStr = res.getString("zip_uri");
            if (res.getBoolean("has_files", false) && zipUriStr != null && !zipUriStr.isEmpty()) {
                if (!target.rootDir.exists()) target.rootDir.mkdirs();
                InputStream in = ctx.getContentResolver().openInputStream(Uri.parse(zipUriStr));
                if (in != null) {
                    try {
                        int count = unzipToDir(in, target.rootDir);
                        Log.i(TAG, "Pre-launch cloud sync unpacked " + count + " file(s) for appId=" + appId);
                    } finally {
                        in.close();
                    }
                }
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "onPreLaunch failed for appId=" + appId + ": " + e.getMessage());
            return false;
        }
    }

    public static boolean onPostExit(Context ctx, int appId, File saveRemoteDir) {
        return onPostExit(ctx, appId, new CloudSaveTarget(saveRemoteDir, "", ""));
    }

    /**
     * Call after a LuaTools game exits. Packs all files from {@code target.getTargetDir()}
     * with paths relative to {@code target.rootDir}, streams them to the Companion App,
     * and uploads them to Google Drive via CloudRedirect.
     */
    public static boolean onPostExit(Context ctx, int appId, CloudSaveTarget target) {
        if (ctx == null || appId <= 0 || target == null || target.rootDir == null
                || !isCompanionInstalled(ctx)) {
            return false;
        }
        File scanDir = target.getTargetDir();
        if (!scanDir.isDirectory()) return false;

        try {
            Uri uploadUri = Uri.parse("content://com.luatools.companion.bridge/cloud_in/" + appId);
            OutputStream out = ctx.getContentResolver().openOutputStream(uploadUri, "wt");
            if (out == null) return false;
            int count;
            try {
                count = zipDirToStream(target.rootDir, scanDir, out);
            } finally {
                out.close();
            }
            if (count <= 0) return false;

            Bundle args = new Bundle();
            args.putInt("app_id", appId);
            args.putString("source_package", ctx.getPackageName());
            args.putBoolean("has_zip", true);
            args.putString("root_token", target.rootToken);
            args.putString("sub_path", target.subPath);
            Bundle res = ctx.getContentResolver().call(
                    COMPANION_BRIDGE_URI, "sync_post_exit", String.valueOf(appId), args);
            boolean ok = res != null && res.getBoolean("ok", false);
            Log.i(TAG, "Post-exit cloud upload appId=" + appId + " files=" + count + " ok=" + ok);
            return ok;
        } catch (Exception e) {
            Log.w(TAG, "onPostExit failed for appId=" + appId + ": " + e.getMessage());
            return false;
        }
    }

    private static int unzipToDir(InputStream in, File destDir) throws Exception {
        int count = 0;
        String canonicalDest = destDir.getCanonicalPath() + File.separator;
        ZipInputStream zis = new ZipInputStream(in);
        byte[] buf = new byte[8192];
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            String name = entry.getName().replace('\\', '/');
            if (name.isEmpty() || name.startsWith("/") || name.contains("..")) continue;
            File outFile = new File(destDir, name);
            if (!outFile.getCanonicalPath().startsWith(canonicalDest)) continue;
            if (entry.isDirectory()) {
                outFile.mkdirs();
            } else {
                if (outFile.isDirectory()) deleteRecursive(outFile);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists()) parent.mkdirs();
                FileOutputStream fos = new FileOutputStream(outFile);
                try {
                    int r;
                    while ((r = zis.read(buf)) != -1) fos.write(buf, 0, r);
                } finally {
                    fos.close();
                }
                count++;
            }
            zis.closeEntry();
        }
        return count;
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteRecursive(c);
        }
        f.delete();
    }

    private static int zipDirToStream(File rootDir, File scanDir, OutputStream out) throws Exception {
        ZipOutputStream zos = new ZipOutputStream(out);
        int count = addDirToZip(rootDir, scanDir, zos);
        zos.finish();
        zos.flush();
        return count;
    }

    private static int addDirToZip(File root, File dir, ZipOutputStream zos) throws Exception {
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int count = 0;
        byte[] buf = new byte[8192];
        String rootPrefix = root.getAbsolutePath();
        for (File f : files) {
            if (f.isDirectory()) {
                count += addDirToZip(root, f, zos);
            } else if (f.isFile()) {
                String abs = f.getAbsolutePath();
                String rel = abs.startsWith(rootPrefix)
                        ? abs.substring(rootPrefix.length() + 1).replace('\\', '/')
                        : f.getName();
                zos.putNextEntry(new ZipEntry(rel));
                FileInputStream fis = new FileInputStream(f);
                try {
                    int r;
                    while ((r = fis.read(buf)) != -1) zos.write(buf, 0, r);
                } finally {
                    fis.close();
                }
                zos.closeEntry();
                count++;
            }
        }
        return count;
    }
}
