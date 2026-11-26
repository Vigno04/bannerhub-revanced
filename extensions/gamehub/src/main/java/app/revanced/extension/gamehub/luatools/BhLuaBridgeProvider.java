package app.revanced.extension.gamehub.luatools;

import android.app.ActivityManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import com.luatools.javasteam.LuaToolsBridgeProvider;
import com.luatools.javasteam.LuaToolsJavaSteamHook;
import com.luatools.javasteam.LuaToolsSteamEmu;
import com.luatools.javasteam.LuaToolsStore;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.revanced.extension.gamehub.gog.BhDownloadService;

/**
 * BannerHub v6 exported bridge provider for LuaTools Android & CloudRedirect.
 * Authority: {@code <variantPackage>.bh_lua_bridge}.
 */
public class BhLuaBridgeProvider extends LuaToolsBridgeProvider {

    private static final String TAG = "BhLuaBridgeV6";
    private static boolean watching;

    @Override
    protected String getAppDisplayName(Context ctx) {
        return "BannerHub v6";
    }

    @Override
    protected File resolveGameInstallDir(Context ctx, int appId, String fallbackName) {
        return SteamCdnDownloader.resolveGameInstallDir(ctx, appId, fallbackName);
    }

    @Override
    protected void onFixDllOverrides(Context ctx, int appId, Set<String> dllStems) {
        BhLuaLaunchHook.saveDllOverrides(ctx, appId, dllStems);
        BhLuaLaunchHook.applyDllOverridesToUserReg(ctx, BhLuaLibraryHelper.gameRowId(appId), dllStems);
    }

    @Override
    protected void onGameAdded(Context ctx, int appId, String name, LuaToolsStore store) {
        try {
            File installDir = resolveGameInstallDir(ctx, appId, name);
            String relExe = SteamCdnDownloader.detectExecutable(ctx, appId, name, installDir, null);
            String fullExe = relExe != null ? new File(installDir, relExe).getAbsolutePath() : "";
            String cover = BhLuaLibraryHelper.steamCapsuleUrl(appId);
            boolean installed = !fullExe.isEmpty() && new File(fullExe).isFile();

            BhLuaLibraryHelper.saveGameMeta(ctx, appId, name, installDir.getAbsolutePath(), fullExe, cover, installed);
            if (installed) {
                LuaToolsSteamEmu.attachGameRuntime(ctx, appId, installDir);
            } else {
                postPushedNotification(ctx, appId, name);
            }
            BhLuaLibraryHelper.registerInLibrary(ctx, appId, name, fullExe, cover);
        } catch (Throwable t) {
            Log.w(TAG, "onGameAdded warning", t);
        }
    }

    private static void postPushedNotification(Context ctx, int appId, String name) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel ch = new NotificationChannel(
                        "bh_downloads", "BannerHub Downloads", NotificationManager.IMPORTANCE_DEFAULT);
                nm.createNotificationChannel(ch);
            }
            Intent openIntent = new Intent(ctx, BhLuaGamesActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(
                    ctx,
                    9200 + (appId % 1000),
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    ? new Notification.Builder(ctx, "bh_downloads")
                    : new Notification.Builder(ctx);
            b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle("LuaTools: " + (name != null && !name.isEmpty() ? name : ("AppID " + appId)))
                    .setContentText("Ready in Explore → LuaTools — tap to download")
                    .setContentIntent(pi)
                    .setAutoCancel(true);
            nm.notify(9200 + (appId % 1000), b.build());
        } catch (Throwable ignored) {}
    }

    @Override
    public boolean onCreate() {
        final Context ctx = getContext();
        if (ctx != null) {
            Thread t = new Thread(() -> watchLoop(ctx.getApplicationContext()), "bh-lua-pcengine-watch");
            t.setDaemon(true);
            t.start();
            Thread syncLib = new Thread(() -> syncAllLuaGamesToLibrary(ctx.getApplicationContext()), "bh-lua-lib-sync");
            syncLib.setDaemon(true);
            syncLib.start();
        }
        return true;
    }

    private static void syncAllLuaGamesToLibrary(Context ctx) {
        try {
            LuaToolsStore store = LuaToolsJavaSteamHook.get(ctx).getStore();
            for (int appId : store.listLuaAppIds()) {
                String name = store.getGameName(appId);
                File installDir = SteamCdnDownloader.resolveGameInstallDir(ctx, appId, name);
                String relExe = SteamCdnDownloader.detectExecutable(ctx, appId, name, installDir, null);
                String fullExe = relExe != null ? new File(installDir, relExe).getAbsolutePath() : "";
                String cover = BhLuaLibraryHelper.steamCapsuleUrl(appId);
                boolean installed = !fullExe.isEmpty() && new File(fullExe).isFile();
                BhLuaLibraryHelper.saveGameMeta(ctx, appId, name, installDir.getAbsolutePath(), fullExe, cover, installed);
                BhLuaLibraryHelper.registerInLibrary(ctx, appId, name, fullExe, cover);
            }
        } catch (Throwable t) {
            Log.w(TAG, "syncAllLuaGamesToLibrary warning: " + t.getMessage());
        }
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context ctx = getContext();
        if (ctx == null || method == null) return super.call(method, arg, extras);

        switch (method) {
            case "ping": {
                Bundle res = super.call(method, arg, extras);
                res.putString("platform_id", "bannerhub_v6");
                res.putString("base_version", "6.3.1");
                res.putBoolean("supports_mt_provider", true);
                res.putString(
                        "mt_authority",
                        ctx.getPackageName() + ".app.revanced.extension.gamehub.filemanager.MTDataFilesProvider"
                );
                res.putBoolean("supports_in_app_download", true);
                return res;
            }
            case "list_prefixes": {
                Bundle res = new Bundle();
                ArrayList<String> prefixes = new ArrayList<>();
                collectPrefixes(ctx.getFilesDir(), prefixes, 0);
                res.putBoolean("ok", true);
                res.putStringArrayList("prefixes", prefixes);
                return res;
            }
            case "add_steam_game": {
                Bundle res = super.call(method, arg, extras);
                if (res != null && res.getBoolean("ok", false) && extras != null) {
                    int appId = extras.getInt("app_id", 0);
                    boolean autoDl = extras.getBoolean("auto_download", false);
                    if (autoDl && appId > 0) {
                        startGameDownload(ctx, appId, res.getString("name", "AppID " + appId));
                    }
                }
                return res;
            }
            case "start_download": {
                int appId = extras != null ? extras.getInt("app_id", 0) : 0;
                Bundle res = new Bundle();
                if (appId <= 0) {
                    res.putBoolean("ok", false);
                    res.putString("error", "Invalid app_id");
                    return res;
                }
                String name = LuaToolsJavaSteamHook.get(ctx).getStore().getGameName(appId);
                startGameDownload(ctx, appId, name);
                res.putBoolean("ok", true);
                res.putInt("app_id", appId);
                return res;
            }
            case "bridge_status": {
                Bundle res = super.call(method, arg, extras);
                res.putBoolean("wine_running", findPcEnginePid(ctx) > 0);
                File marker = BhLuaLaunchHook.activeLaunchFile(ctx);
                if (marker.isFile()) {
                    try {
                        JSONObject act = new JSONObject(new String(LuaToolsStore.readAllBytes(marker), "UTF-8"));
                        res.putString("last_app_id", String.valueOf(act.optInt("app_id", 0)));
                        res.putString("last_prefix", act.optString("prefix_dir", ""));
                    } catch (Throwable ignored) {}
                }
                return res;
            }
            default:
                return super.call(method, arg, extras);
        }
    }

    public static void startGameDownload(Context ctx, int appId, String name) {
        String safeName = name != null && !name.isEmpty() ? name : ("AppID " + appId);
        Intent i = new Intent(ctx, BhDownloadService.class);
        i.setAction(BhDownloadService.ACTION_START);
        i.putExtra(BhDownloadService.EXTRA_STORE, "STEAM_LUA");
        i.putExtra(BhDownloadService.EXTRA_GAME_ID, "steam_lua_" + appId);
        i.putExtra(BhDownloadService.EXTRA_GAME_NAME, safeName);
        i.putExtra("steam_lua_app_id", appId);
        try {
            ctx.startForegroundService(i);
        } catch (Throwable fgsErr) {
            // Android 14+/16 blocks startForegroundService from a background ContentProvider call.
            // Fall back to in-process SteamCdnDownloader with regular NotificationManager updates.
            Log.w(TAG, "startForegroundService blocked (" + fgsErr.getMessage() + "), using in-process fallback");
            startDownloadFallback(ctx.getApplicationContext(), appId, safeName);
        }
    }

    private static void startDownloadFallback(Context ctx, int appId, String safeName) {
        final int notifId = 9400 + (appId % 1000);
        final NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(
                    "bh_downloads", "BannerHub Downloads", NotificationManager.IMPORTANCE_LOW));
        }
        SteamCdnDownloader.startDownload(ctx, appId, 4, new SteamCdnDownloader.Callback() {
            private long lastNotifMs = 0L;

            @Override public void onProgress(String msg, int pct) {
                long now = System.currentTimeMillis();
                if (nm == null || (now - lastNotifMs < 1200L && pct < 100)) return;
                lastNotifMs = now;
                try {
                    Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            ? new Notification.Builder(ctx, "bh_downloads")
                            : new Notification.Builder(ctx);
                    b.setSmallIcon(android.R.drawable.stat_sys_download)
                            .setContentTitle(safeName)
                            .setContentText(msg)
                            .setProgress(100, pct, pct == 0)
                            .setOngoing(true);
                    nm.notify(notifId, b.build());
                } catch (Throwable ignored) {}
            }

            @Override public void onComplete(String installDir, String exePath) {
                if (nm == null) return;
                try {
                    Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            ? new Notification.Builder(ctx, "bh_downloads")
                            : new Notification.Builder(ctx);
                    b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                            .setContentTitle(safeName + " — installed")
                            .setContentText("Ready to play in BannerHub")
                            .setAutoCancel(true);
                    nm.notify(notifId, b.build());
                } catch (Throwable ignored) {}
            }

            @Override public void onError(String msg) {
                if (nm == null) return;
                try {
                    Notification.Builder b = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            ? new Notification.Builder(ctx, "bh_downloads")
                            : new Notification.Builder(ctx);
                    b.setSmallIcon(android.R.drawable.stat_notify_error)
                            .setContentTitle(safeName + " — download failed")
                            .setContentText(msg)
                            .setAutoCancel(true);
                    nm.notify(notifId, b.build());
                } catch (Throwable ignored) {}
            }

            @Override public void onCancelled() {
                if (nm != null) nm.cancel(notifId);
            }
        });
    }

    private static void watchLoop(Context ctx) {
        synchronized (BhLuaBridgeProvider.class) {
            if (watching) return;
            watching = true;
        }
        boolean wasRunning = false;
        int runningApp = 0;
        String runningPrefix = "";
        String runningExe = "";
        int sawExePolls = 0;
        long procStartedAt = 0L;
        long exeGoneSince = 0L;

        while (true) {
            try {
                int pid = findPcEnginePid(ctx);
                boolean running = pid > 0;
                if (running && !wasRunning) {
                    sawExePolls = 0;
                    exeGoneSince = 0L;
                    procStartedAt = System.currentTimeMillis();
                    runningApp = 0;
                    runningPrefix = "";
                    runningExe = "";

                    File marker = BhLuaLaunchHook.activeLaunchFile(ctx);
                    if (marker.isFile()) {
                        try {
                            JSONObject act = new JSONObject(new String(LuaToolsStore.readAllBytes(marker), "UTF-8"));
                            runningApp = act.optInt("app_id", 0);
                            runningPrefix = act.optString("prefix_dir", "");
                            runningExe = act.optString("exe_name", "");
                        } catch (Throwable ignored) {}
                    }
                } else if (running && LuaToolsSteamEmu.autoCloseEnabled(ctx) && !runningExe.isEmpty()) {
                    long now = System.currentTimeMillis();
                    if (isExeRunningInProc(runningExe)) {
                        sawExePolls++;
                        exeGoneSince = 0L;
                    } else if (sawExePolls >= 2 && (now - procStartedAt) >= 15000L) {
                        if (exeGoneSince == 0L) {
                            exeGoneSince = now;
                        } else if (now - exeGoneSince >= 4000L) {
                            android.os.Process.killProcess(pid);
                        }
                    }
                } else if (!running && wasRunning) {
                    // Fallback post-exit sync if :pcengine died without Activity.onDestroy firing
                    File marker = BhLuaLaunchHook.activeLaunchFile(ctx);
                    if (marker.isFile()) {
                        try {
                            JSONObject act = new JSONObject(new String(LuaToolsStore.readAllBytes(marker), "UTF-8"));
                            int appId = act.optInt("app_id", 0);
                            String prefix = act.optString("prefix_dir", "");
                            marker.delete();
                            if (appId > 0 && !prefix.isEmpty()) {
                                BhLuaCloudHook.onPostExit(ctx, appId, prefix);
                            }
                        } catch (Throwable ignored) {}
                    } else if (runningApp > 0 && !runningPrefix.isEmpty()) {
                        // Already handled by onHostActivityDestroy
                    }
                    runningApp = 0;
                    runningPrefix = "";
                    runningExe = "";
                    sawExePolls = 0;
                    exeGoneSince = 0L;
                }
                wasRunning = running;
            } catch (Throwable ignored) {}
            try { Thread.sleep(2000); } catch (InterruptedException e) { return; }
        }
    }

    private static int findPcEnginePid(Context ctx) {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ActivityManager.RunningAppProcessInfo> list = am != null ? am.getRunningAppProcesses() : null;
            if (list == null) return -1;
            for (ActivityManager.RunningAppProcessInfo p : list) {
                if (p.processName != null && (p.processName.endsWith(":pcengine") || p.processName.endsWith(":wine"))) {
                    return p.pid;
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static boolean isExeRunningInProc(String exeName) {
        if (exeName == null || exeName.isEmpty()) return false;
        String needle = exeName.toLowerCase();
        String stem = needle.endsWith(".exe") ? needle.substring(0, needle.length() - 4) : needle;
        String commNeedle = needle.length() > 15 ? needle.substring(0, 15) : needle;
        String stemComm = stem.length() > 15 ? stem.substring(0, 15) : stem;
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return false;
        for (File p : procs) {
            String n = p.getName();
            if (n.isEmpty() || n.charAt(0) < '0' || n.charAt(0) > '9') continue;
            File cmd = new File(p, "cmdline");
            if (cmd.isFile()) {
                try {
                    byte[] b = readBytesSmall(cmd);
                    if (b.length > 0) {
                        String s = new String(b, "UTF-8").replace('\0', ' ').toLowerCase();
                        if (s.contains(needle) || s.contains(stem)) return true;
                    }
                } catch (Throwable ignored) {}
            }
            File comm = new File(p, "comm");
            if (comm.isFile()) {
                try {
                    String c = new String(readBytesSmall(comm), "UTF-8").trim().toLowerCase();
                    if (!c.isEmpty() && (c.equals(commNeedle) || c.startsWith(stemComm))) return true;
                } catch (Throwable ignored) {}
            }
        }
        return false;
    }

    private static byte[] readBytesSmall(File f) throws Exception {
        try (FileInputStream fis = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[512];
            int r;
            while ((r = fis.read(buf)) != -1) bos.write(buf, 0, r);
            return bos.toByteArray();
        }
    }

    private static void collectPrefixes(File dir, ArrayList<String> out, int depth) {
        if (depth > 3 || dir == null || !dir.isDirectory()) return;
        File driveC = new File(dir, "drive_c");
        if (driveC.isDirectory()) {
            out.add(dir.getAbsolutePath());
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectPrefixes(child, out, depth + 1);
        }
    }
}
