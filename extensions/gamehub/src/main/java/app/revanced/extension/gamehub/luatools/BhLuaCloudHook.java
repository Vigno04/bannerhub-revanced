package app.revanced.extension.gamehub.luatools;

import android.content.Context;

import com.luatools.javasteam.LuaToolsCloudSync;

import java.io.File;

/**
 * BannerHub v6 adapter delegating pre-launch / post-exit CloudRedirect sync
 * to the universal {@link LuaToolsCloudSync} addon.
 */
public final class BhLuaCloudHook {

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
        File saveDir = resolveSaveDir(ctx, appId, winePrefix);
        if (saveDir != null) {
            LuaToolsCloudSync.onPreLaunch(ctx, appId, saveDir);
        }
    }

    public static void onPostExit(Context ctx, int appId, String winePrefix) {
        if (winePrefix == null || winePrefix.isEmpty()) return;
        File saveDir = resolveSaveDir(ctx, appId, winePrefix);
        if (saveDir != null) {
            LuaToolsCloudSync.onPostExit(ctx, appId, saveDir);
        }
    }
}
