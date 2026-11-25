package app.revanced.extension.gamehub.luatools;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.luatools.javasteam.LuaToolsJavaSteamHook;
import com.luatools.javasteam.LuaToolsSteamEmu;
import com.luatools.javasteam.LuaToolsStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.lang.reflect.Field;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Launch/exit lifecycle hook running in {@code :pcengine} inside
 * {@code PcEnginePluginHostActivity.onCreate} and {@code onDestroy}.
 *
 * Inspects the un-obfuscated {@code com.winemu.openapi.Config} Parcelable carried on
 * the host->plugin Intent via reflection so R8 reshuffles never break it.
 */
public final class BhLuaLaunchHook {

    private static final String TAG = "BhLuaLaunchHook";
    private static final AtomicBoolean LAUNCH_HOOK_DONE = new AtomicBoolean(false);

    private BhLuaLaunchHook() {}

    public static File activeLaunchFile(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "luatools");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, "active_launch.json");
    }

    public static File dllOverridesFile(Context ctx, int appId) {
        File dir = new File(ctx.getFilesDir(), "luatools/dll_overrides");
        if (!dir.exists()) dir.mkdirs();
        return new File(dir, appId + ".json");
    }

    public static void saveDllOverrides(Context ctx, int appId, Set<String> dllStems) {
        try {
            Set<String> merged = new LinkedHashSet<>(loadDllOverrides(ctx, appId));
            if (dllStems != null) merged.addAll(dllStems);
            JSONArray arr = new JSONArray();
            for (String s : merged) arr.put(s);
            JSONObject obj = new JSONObject();
            obj.put("app_id", appId);
            obj.put("dlls", arr);
            LuaToolsStore.writeAllBytes(dllOverridesFile(ctx, appId), obj.toString().getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "saveDllOverrides warning", t);
        }
    }

    public static Set<String> loadDllOverrides(Context ctx, int appId) {
        Set<String> out = new LinkedHashSet<>();
        File f = dllOverridesFile(ctx, appId);
        if (!f.isFile()) return out;
        try {
            JSONObject obj = new JSONObject(new String(LuaToolsStore.readAllBytes(f), "UTF-8"));
            JSONArray arr = obj.optJSONArray("dlls");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    String s = arr.optString(i, "").trim();
                    if (!s.isEmpty()) out.add(s);
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    public static void onHostActivityCreate(Activity activity) {
        if (activity == null) return;
        try {
            final Context ctx = activity.getApplicationContext();
            Intent intent = activity.getIntent();
            if (intent == null) return;
            Bundle extras = intent.getExtras();
            if (extras == null) return;

            Object configObj = findOpenApiConfig(extras);
            String gameId = readStringFieldOrExtra(configObj, extras, "gameId", "game_id", "localGameId");
            String exePath = readStringFieldOrExtra(configObj, extras, "exePath", "exe_path");
            String gameRootDir = readStringFieldOrExtra(configObj, extras, "gameRootDir", "game_root_dir");
            int steamAppId = readIntFieldOrExtra(configObj, extras, "steamAppId", "steam_app_id");

            int appId = resolveLuaAppId(ctx, gameId, steamAppId, exePath, gameRootDir);
            if (appId <= 0 || !LuaToolsJavaSteamHook.get(ctx).isLuaGame(appId)) {
                return;
            }
            if (gameId == null || gameId.isEmpty()) {
                gameId = BhLuaLibraryHelper.gameRowId(appId);
            }

            File installDir = SteamCdnDownloader.resolveGameInstallDir(ctx, appId, "");
            if (gameRootDir != null && !gameRootDir.isEmpty()) {
                File f = new File(gameRootDir);
                if (f.isDirectory()) installDir = f;
            }

            // 1. Ensure SteamEmu runtime (steam_appid.txt + Goldberg/GSE DLLs if needed)
            try {
                LuaToolsSteamEmu.attachGameRuntime(ctx, appId, installDir);
            } catch (Throwable t) {
                Log.w(TAG, "attachGameRuntime warning: " + t.getMessage());
            }

            // 2. Apply saved Online-Fix / proxy DLL overrides into Config + user.reg
            Set<String> dlls = loadDllOverrides(ctx, appId);
            if (!dlls.isEmpty()) {
                if (configObj != null) {
                    injectDllOverridesIntoConfig(configObj, dlls);
                }
                applyDllOverridesToUserReg(ctx, gameId, dlls);
            }

            // 3. Determine prefix dir & exe basename
            String prefixDir = new File(ctx.getFilesDir(), "usr/home/virtual_containers/" + gameId).getAbsolutePath();
            String exeName = "";
            if (exePath != null && !exePath.isEmpty()) {
                String norm = exePath.replace('\\', '/');
                int slash = norm.lastIndexOf('/');
                exeName = slash >= 0 ? norm.substring(slash + 1) : norm;
            }

            // 4. Write active_launch.json for post-exit watcher & CloudRedirect
            JSONObject active = new JSONObject();
            active.put("app_id", appId);
            active.put("game_id", gameId);
            active.put("prefix_dir", prefixDir);
            active.put("exe_name", exeName);
            active.put("started_at", System.currentTimeMillis());
            LuaToolsStore.writeAllBytes(activeLaunchFile(ctx), active.toString().getBytes("UTF-8"));

            // 5. Pull CloudRedirect saves synchronously (bounded 5s so UI never ANRs)
            if (LAUNCH_HOOK_DONE.compareAndSet(false, true)) {
                final int fAppId = appId;
                final String fPrefix = prefixDir;
                Thread syncThread = new Thread(() -> {
                    try {
                        BhLuaCloudHook.onPreLaunch(ctx, fAppId, fPrefix);
                    } catch (Throwable t) {
                        Log.w(TAG, "onPreLaunch warning: " + t.getMessage());
                    }
                }, "bh-lua-presync");
                syncThread.start();
                try { syncThread.join(5000L); } catch (InterruptedException ignored) {}
            }
            Log.i(TAG, "LuaTools pre-launch ready appId=" + appId + " gameId=" + gameId + " dlls=" + dlls);
        } catch (Throwable t) {
            Log.w(TAG, "onHostActivityCreate warning", t);
        }
    }

    public static void onHostActivityDestroy(Activity activity) {
        if (activity == null) return;
        LAUNCH_HOOK_DONE.set(false);
        final Context ctx = activity.getApplicationContext();
        final File marker = activeLaunchFile(ctx);
        if (!marker.isFile()) return;
        new Thread(() -> {
            try {
                if (!marker.isFile()) return;
                JSONObject active = new JSONObject(new String(LuaToolsStore.readAllBytes(marker), "UTF-8"));
                int appId = active.optInt("app_id", 0);
                String prefixDir = active.optString("prefix_dir", "");
                marker.delete();
                if (appId > 0 && !prefixDir.isEmpty()) {
                    BhLuaCloudHook.onPostExit(ctx, appId, prefixDir);
                }
            } catch (Throwable t) {
                Log.w(TAG, "onHostActivityDestroy post-sync warning: " + t.getMessage());
            }
        }, "bh-lua-postsync").start();
    }

    private static Object findOpenApiConfig(Bundle extras) {
        for (String key : extras.keySet()) {
            try {
                Object v = extras.get(key);
                if (v != null && v.getClass().getName().equals("com.winemu.openapi.Config")) {
                    return v;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static String readStringFieldOrExtra(Object cfg, Bundle extras, String... names) {
        if (cfg != null) {
            for (String n : names) {
                try {
                    Field f = findField(cfg.getClass(), n);
                    if (f != null) {
                        f.setAccessible(true);
                        Object v = f.get(cfg);
                        if (v != null && !v.toString().isEmpty()) return v.toString();
                    }
                } catch (Throwable ignored) {}
            }
        }
        for (String n : names) {
            String v = extras.getString(n);
            if (v != null && !v.isEmpty()) return v;
        }
        return "";
    }

    private static int readIntFieldOrExtra(Object cfg, Bundle extras, String... names) {
        if (cfg != null) {
            for (String n : names) {
                try {
                    Field f = findField(cfg.getClass(), n);
                    if (f != null) {
                        f.setAccessible(true);
                        Object v = f.get(cfg);
                        if (v instanceof Number && ((Number) v).intValue() > 0) {
                            return ((Number) v).intValue();
                        }
                        if (v instanceof String && !((String) v).isEmpty()) {
                            return Integer.parseInt((String) v);
                        }
                    }
                } catch (Throwable ignored) {}
            }
        }
        for (String n : names) {
            int iv = extras.getInt(n, 0);
            if (iv > 0) return iv;
        }
        return 0;
    }

    private static int resolveLuaAppId(Context ctx, String gameId, int steamAppId, String exePath, String gameRootDir) {
        if (gameId != null && gameId.startsWith("lua_")) {
            try {
                return Integer.parseInt(gameId.substring(4));
            } catch (NumberFormatException ignored) {}
        }
        if (steamAppId > 0) return steamAppId;

        File[] metas = BhLuaLibraryHelper.metaDir(ctx).listFiles();
        if (metas != null) {
            for (File f : metas) {
                if (!f.getName().endsWith(".json")) continue;
                try {
                    JSONObject o = new JSONObject(new String(LuaToolsStore.readAllBytes(f), "UTF-8"));
                    String dir = o.optString("install_dir", "");
                    String exe = o.optString("exe_path", "");
                    if ((!dir.isEmpty() && ((gameRootDir != null && gameRootDir.startsWith(dir))
                            || (exePath != null && exePath.startsWith(dir))))
                            || (!exe.isEmpty() && exe.equals(exePath))) {
                        return o.optInt("app_id", 0);
                    }
                } catch (Throwable ignored) {}
            }
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static void injectDllOverridesIntoConfig(Object cfg, Set<String> dlls) {
        try {
            Field f = findField(cfg.getClass(), "dllOverrides");
            if (f == null) return;
            f.setAccessible(true);
            Object cur = f.get(cfg);
            if (cur == null || cur instanceof String) {
                String existing = cur != null ? cur.toString() : "";
                StringBuilder sb = new StringBuilder(existing);
                for (String d : dlls) {
                    if (!existing.contains(d + "=")) {
                        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ';') sb.append(';');
                        sb.append(d).append("=n,b");
                    }
                }
                f.set(cfg, sb.toString());
            } else if (cur instanceof java.util.Map) {
                java.util.Map<Object, Object> m = (java.util.Map<Object, Object>) cur;
                for (String d : dlls) {
                    if (!m.containsKey(d)) m.put(d, "native,builtin");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "injectDllOverridesIntoConfig warning: " + t.getMessage());
        }
    }

    public static void applyDllOverridesToUserReg(Context ctx, String gameId, Set<String> dlls) {
        if (gameId == null || gameId.isEmpty() || dlls == null || dlls.isEmpty()) return;
        try {
            File reg = new File(ctx.getFilesDir(), "usr/home/virtual_containers/" + gameId + "/user.reg");
            if (!reg.isFile()) return;
            String content = new String(LuaToolsStore.readAllBytes(reg), "UTF-8");
            int idx = content.indexOf("[Software\\\\Wine\\\\DllOverrides]");
            if (idx < 0) return;
            int nl = content.indexOf('\n', idx);
            if (nl < 0) return;
            int insertPos = nl + 1;
            if (content.startsWith("#time=", insertPos)) {
                int nl2 = content.indexOf('\n', insertPos);
                if (nl2 >= 0) insertPos = nl2 + 1;
            }
            StringBuilder add = new StringBuilder();
            for (String dll : dlls) {
                String line = "\"" + dll + "\"=\"native,builtin\"";
                if (!content.contains("\"" + dll + "\"=")) {
                    add.append(line).append('\n');
                }
            }
            if (add.length() > 0) {
                String updated = content.substring(0, insertPos) + add + content.substring(insertPos);
                LuaToolsStore.writeAllBytes(reg, updated.getBytes("UTF-8"));
            }
        } catch (Throwable t) {
            Log.w(TAG, "applyDllOverridesToUserReg warning: " + t.getMessage());
        }
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
}
