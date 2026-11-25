package com.luatools.javasteam;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Universal JavaSteam (in.dragonbra.javasteam) helper & hook for Android Steam emulators
 * (GameNative, Pluvia, BannerHub, GameHub, Winlator, etc.).
 *
 * Uses reflection against standard in.dragonbra.javasteam.* classes so it drops into
 * any JavaSteam version without Gradle/protobuf version conflicts.
 *
 * Typical 10-minute integration:
 *   1. LuaToolsJavaSteamHook hook = LuaToolsJavaSteamHook.get(context);
 *   2. Library list: merge hook.getLuaAppIds() with Steam's LicenseListCallback appIds.
 *   3. Depot key:    check hook.getDepotKey(depotId) before calling SteamApps.getDepotDecryptionKey().
 *   4. Manifest:     check hook.loadDecryptedDepotManifest(depotId, manifestId) before calling SteamContent.getManifestRequestCode().
 *   5. Launch:       if (hook.isLuaGame(appId)) launch with fake/Goldberg Steam + LuaToolsCloudSync.onPreLaunch/onPostExit.
 */
public final class LuaToolsJavaSteamHook {

    private static final String TAG = "LuaToolsJavaSteam";
    private static volatile LuaToolsJavaSteamHook instance;

    private final LuaToolsStore store;

    private LuaToolsJavaSteamHook(Context ctx) {
        this.store = new LuaToolsStore(ctx.getApplicationContext());
    }

    public static LuaToolsJavaSteamHook get(Context ctx) {
        if (instance == null) {
            synchronized (LuaToolsJavaSteamHook.class) {
                if (instance == null) {
                    instance = new LuaToolsJavaSteamHook(ctx);
                }
            }
        }
        return instance;
    }

    public LuaToolsStore getStore() {
        return store;
    }

    /** Returns true if appId was installed via LuaTools (.lua present). */
    public boolean isLuaGame(int appId) {
        return store.isLuaGame(appId);
    }

    /** Returns all AppIDs installed via LuaTools so the emulator can merge them into its library. */
    public List<Integer> getLuaAppIds() {
        return store.listLuaAppIds();
    }

    /**
     * Returns the 32-byte depot decryption key from local .lua files, or null if not a LuaTools depot.
     * Call this BEFORE SteamApps.getDepotDecryptionKey(depotId, appId) to avoid AccessDenied.
     */
    public byte[] getDepotKey(int depotId) {
        return store.getDepotKey(depotId);
    }

    /**
     * Loads and decrypts a JavaSteam {@code in.dragonbra.javasteam.types.DepotManifest} from
     * local depotcache/<depotId>_<manifestId>.manifest using the .lua depot key.
     *
     * Call this BEFORE SteamContent.getManifestRequestCode(depotId, appId, manifestId) to avoid
     * AccessDenied when downloading unowned LuaTools games.
     *
     * @return An {@code in.dragonbra.javasteam.types.DepotManifest} instance (cast by caller), or null.
     */
    public Object loadDecryptedDepotManifest(int depotId, long manifestId) {
        File manifestFile = store.getManifestFile(depotId, manifestId);
        if (manifestFile == null) return null;
        try {
            byte[] raw = LuaToolsStore.readAllBytes(manifestFile);
            Class<?> dmClass = Class.forName("in.dragonbra.javasteam.types.DepotManifest");
            Method deserialize = dmClass.getMethod("deserialize", byte[].class);
            Object dm = deserialize.invoke(null, (Object) raw);

            byte[] key = store.getDepotKey(depotId);
            if (key != null && key.length == 32) {
                Method decrypt = dmClass.getMethod("decryptFilenames", byte[].class);
                decrypt.invoke(dm, (Object) key);
            }
            return dm;
        } catch (Throwable t) {
            Log.w(TAG, "loadDecryptedDepotManifest(" + depotId + ", " + manifestId + ") failed: " + t.getMessage());
            return null;
        }
    }

    /**
     * Convenience helper: decrypts and saves a DepotManifest to an emulator's own manifest cache path
     * (e.g. files/steam_download_cache/manifest/<manifestId>) using JavaSteam's DepotManifest.saveToFile().
     */
    public boolean exportDecryptedManifestToFile(int depotId, long manifestId, File destFile) {
        Object dm = loadDecryptedDepotManifest(depotId, manifestId);
        if (dm == null) return false;
        try {
            File parent = destFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            Method saveToFile = dm.getClass().getMethod("saveToFile", String.class);
            saveToFile.invoke(dm, destFile.getAbsolutePath());
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "exportDecryptedManifestToFile failed: " + t.getMessage());
            return false;
        }
    }
}
