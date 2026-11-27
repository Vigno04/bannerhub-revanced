package app.revanced.extension.gamehub.luatools;

import android.content.Context;
import android.util.Log;

import com.luatools.javasteam.LuaToolsCloudSync;
import com.luatools.javasteam.LuaToolsJavaSteamHook;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.Iterator;

/**
 * BannerHub v6 adapter delegating pre-launch / post-exit CloudRedirect sync
 * to the universal {@link LuaToolsCloudSync} addon.
 */
public final class BhLuaCloudHook {

    private static final String TAG = "BhLuaCloudHook";

    private BhLuaCloudHook() {}

    public static boolean isCompanionInstalled(Context ctx) {
        return LuaToolsCloudSync.isCompanionInstalled(ctx);
    }

    public static File resolveSaveDir(Context ctx, int appId, String winePrefix) {
        if (winePrefix == null || winePrefix.isEmpty()) return null;
        return LuaToolsCloudSync.resolveCloudSaveDir(ctx, appId, new File(winePrefix), "steamuser", "");
    }

    public static void onPreLaunch(Context ctx, int appId, String winePrefix) {
        if (winePrefix == null || winePrefix.isEmpty()) return;
        File prefix = new File(winePrefix);
        LuaToolsCloudSync.CloudSaveTarget target =
                LuaToolsCloudSync.resolveCloudSaveTarget(ctx, appId, prefix, "steamuser", "");
        boolean ok = LuaToolsCloudSync.onPreLaunch(ctx, appId, target);
        if (ok) {
            mirrorSteamIdSaves(ctx, appId, prefix, false);
        }
    }

    public static void onPostExit(Context ctx, int appId, String winePrefix) {
        if (winePrefix == null || winePrefix.isEmpty()) return;
        File prefix = new File(winePrefix);
        mirrorSteamIdSaves(ctx, appId, prefix, true);
        LuaToolsCloudSync.CloudSaveTarget target =
                LuaToolsCloudSync.resolveCloudSaveTarget(ctx, appId, prefix, "steamuser", "");
        LuaToolsCloudSync.onPostExit(ctx, appId, target);
    }

    /**
     * Bridges SteamID64-scoped cloud saves (e.g. {@code .../Steam/7656119.../SaveGames})
     * with fake-Steam / SteamId=-1 local saves (e.g. {@code .../Steam/SaveGames})
     * when {@code ufs.savefiles} declares {@code {64BitSteamID}}.
     *
     * @param toSteamIdDir false on pre-launch (copy SteamID64 -> no-SteamID),
     *                     true on post-exit (copy newer no-SteamID -> SteamID64).
     */
    private static void mirrorSteamIdSaves(Context ctx, int appId, File prefix, boolean toSteamIdDir) {
        try {
            JSONObject pics = LuaToolsJavaSteamHook.get(ctx).getStore().getPicsInfo(appId);
            JSONObject ufs = pics != null ? pics.optJSONObject("ufs") : null;
            JSONObject savefiles = ufs != null ? ufs.optJSONObject("savefiles") : null;
            if (savefiles == null) return;

            Iterator<String> keys = savefiles.keys();
            while (keys.hasNext()) {
                JSONObject entry = savefiles.optJSONObject(keys.next());
                if (entry == null) continue;
                String relPath = entry.optString("path", "").replace('\\', '/');
                int tokenIdx = relPath.indexOf("{64BitSteamID}");
                if (tokenIdx < 0) continue;

                String root = entry.optString("root", "");
                File baseDir;
                if (root.equalsIgnoreCase("WinMyDocuments") || root.equalsIgnoreCase("SteamAutoSaveRoot")) {
                    baseDir = new File(prefix, "drive_c/users/steamuser/Documents");
                } else if (root.equalsIgnoreCase("WinAppDataRoaming")) {
                    baseDir = new File(prefix, "drive_c/users/steamuser/AppData/Roaming");
                } else if (root.equalsIgnoreCase("WinAppDataLocal")) {
                    baseDir = new File(prefix, "drive_c/users/steamuser/AppData/Local");
                } else if (root.equalsIgnoreCase("WinAppDataLocalLow")) {
                    baseDir = new File(prefix, "drive_c/users/steamuser/AppData/LocalLow");
                } else if (root.equalsIgnoreCase("WinSavedGames")) {
                    baseDir = new File(prefix, "drive_c/users/steamuser/Saved Games");
                } else {
                    continue;
                }

                String prefixRel = relPath.substring(0, tokenIdx).replaceAll("^/+|/+$", "");
                String suffixRel = relPath.substring(tokenIdx + "{64BitSteamID}".length()).replaceAll("^/+|/+$", "");
                File parent = prefixRel.isEmpty() ? baseDir : new File(baseDir, prefixRel);
                if (!parent.isDirectory()) continue;

                File steamIdDir = null;
                File[] children = parent.listFiles();
                if (children != null) {
                    for (File c : children) {
                        if (c.isDirectory() && c.getName().matches("7656119\\d{10}")) {
                            steamIdDir = c;
                            break;
                        }
                    }
                }
                if (steamIdDir == null) continue;

                File withId = suffixRel.isEmpty() ? steamIdDir : new File(steamIdDir, suffixRel);
                File withoutId = suffixRel.isEmpty() ? parent : new File(parent, suffixRel);
                if (toSteamIdDir) {
                    if (withoutId.isDirectory()) {
                        int copied = copyNewerFiles(withoutId, withId);
                        if (copied > 0) Log.i(TAG, "Mirrored " + copied + " save(s) to SteamID64 dir for " + appId);
                    }
                } else {
                    if (withId.isDirectory()) {
                        int copied = copyNewerFiles(withId, withoutId);
                        if (copied > 0) Log.i(TAG, "Mirrored " + copied + " save(s) to local dir for " + appId);
                    }
                }
                return;
            }
        } catch (Throwable t) {
            Log.w(TAG, "mirrorSteamIdSaves warning: " + t.getMessage());
        }
    }

    private static int copyNewerFiles(File srcDir, File dstDir) {
        File[] files = srcDir.listFiles();
        if (files == null) return 0;
        if (!dstDir.exists()) dstDir.mkdirs();
        int count = 0;
        byte[] buf = new byte[8192];
        for (File f : files) {
            File dst = new File(dstDir, f.getName());
            if (f.isDirectory()) {
                count += copyNewerFiles(f, dst);
            } else if (f.isFile()) {
                if (dst.isFile() && dst.lastModified() >= f.lastModified() && dst.length() == f.length()) {
                    continue;
                }
                try (FileInputStream in = new FileInputStream(f);
                     FileOutputStream out = new FileOutputStream(dst)) {
                    int r;
                    while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
                    dst.setLastModified(f.lastModified());
                    count++;
                } catch (Throwable ignored) {}
            }
        }
        return count;
    }
}
