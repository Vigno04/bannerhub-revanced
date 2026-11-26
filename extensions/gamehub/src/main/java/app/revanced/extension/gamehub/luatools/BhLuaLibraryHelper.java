package app.revanced.extension.gamehub.luatools;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import com.luatools.javasteam.LuaToolsStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Registers, updates, and removes LuaTools-downloaded Steam games in GameHub 6.3.1's
 * {@code db_game_library.db} ({@code t_game_library_base} + {@code t_game_launch_method}).
 *
 * Mirrors {@link app.revanced.extension.gamehub.gog.GogLaunchHelper} for the row shape
 * (LaunchType.GogGameByPcEmulator, start_type=1409) and uses Room's BundledSQLiteDriver
 * (matching {@link app.revanced.extension.gamehub.login.FakeAccountDbSeeder}) so POSIX locks
 * never collide with Room's in-process connection.
 */
public final class BhLuaLibraryHelper {

    private static final String TAG = "BhLuaLib";
    private static final String DB_NAME = "db_game_library.db";
    private static final int START_TYPE_PC_EMU = 1409;
    private static final String FALLBACK_USER_ID = "99999";
    private static final int FALLBACK_EXT_TYPE = 1;

    private static final String DRIVER_CLASS = "androidx.sqlite.driver.bundled.BundledSQLiteDriver";
    private static final String CONNECTION_IFACE = "androidx.sqlite.SQLiteConnection";
    private static final String STATEMENT_IFACE = "androidx.sqlite.SQLiteStatement";

    private BhLuaLibraryHelper() {}

    public static String gameRowId(int appId) {
        return String.valueOf(appId);
    }

    public static File metaDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "luatools/games");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    public static void saveGameMeta(
            Context ctx, int appId, String name, String installDir, String exePath, String coverUrl, boolean installed
    ) {
        try {
            JSONObject o = new JSONObject();
            o.put("app_id", appId);
            o.put("game_row_id", gameRowId(appId));
            o.put("name", name != null ? name : ("AppID " + appId));
            o.put("install_dir", installDir != null ? installDir : "");
            o.put("exe_path", exePath != null ? exePath : "");
            o.put("cover_url", coverUrl != null ? coverUrl : steamHeaderUrl(appId));
            o.put("installed", installed);
            o.put("updated_at", System.currentTimeMillis());
            LuaToolsStore.writeAllBytes(new File(metaDir(ctx), appId + ".json"), o.toString().getBytes("UTF-8"));
        } catch (Throwable t) {
            Log.w(TAG, "saveGameMeta failed for " + appId, t);
        }
    }

    public static JSONObject loadGameMeta(Context ctx, int appId) {
        File f = new File(metaDir(ctx), appId + ".json");
        if (!f.isFile()) return null;
        try {
            return new JSONObject(new String(LuaToolsStore.readAllBytes(f), "UTF-8"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void deleteGameMeta(Context ctx, int appId) {
        File f = new File(metaDir(ctx), appId + ".json");
        if (f.isFile()) f.delete();
    }

    public static String steamCapsuleUrl(int appId) {
        return "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/" + appId + "/library_600x900.jpg";
    }

    public static String steamHeaderUrl(int appId) {
        return "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/" + appId + "/header.jpg";
    }

    public static String steamHeroUrl(int appId) {
        return "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/" + appId + "/library_hero.jpg";
    }

    public static String steamLogoUrl(int appId) {
        return "https://shared.fastly.steamstatic.com/store_item_assets/steam/apps/" + appId + "/logo.png";
    }

    /** Registers or updates the game in db_game_library.db so it appears in GameHub's library. */
    public static boolean registerInLibrary(
            Context ctx, int appId, String name, String exeFullPath, String coverUrl
    ) {
        if (ctx == null || appId <= 0) return false;
        String safeExe = exeFullPath != null ? exeFullPath : "";
        String safeName = (name != null && !name.isEmpty()) ? name : ("AppID " + appId);
        String safeCover = (coverUrl != null && !coverUrl.isEmpty()) ? coverUrl : steamCapsuleUrl(appId);
        String rowId = gameRowId(appId);
        SteamMeta meta = fetchSteamMeta(ctx, appId);

        File dbFile = ctx.getDatabasePath(DB_NAME);
        if (dbFile == null || !dbFile.exists()) {
            Log.w(TAG, DB_NAME + " does not exist yet (open GameHub library once first)");
            return false;
        }

        // Prefer BundledSQLiteDriver (matches Room's native SQLite connection)
        if (registerViaBundledDriver(dbFile, rowId, appId, safeName, safeCover, safeExe, meta)) {
            return true;
        }
        return registerViaPlatformSqlite(dbFile, rowId, appId, safeName, safeCover, safeExe, meta);
    }

    private static final class SteamMeta {
        String description = "";
        String developer = "";
        String publisher = "";
        String releaseDate = "";
        String categories = "";
        String tags = "";
        String langs = "";
        String screenshots = "[]";
    }

    private static SteamMeta fetchSteamMeta(Context ctx, int appId) {
        SteamMeta m = new SteamMeta();
        try {
            File cacheDir = new File(ctx.getFilesDir(), "luatools/pics_cache");
            if (!cacheDir.exists()) cacheDir.mkdirs();
            File cacheFile = new File(cacheDir, "store_" + appId + ".json");
            JSONObject data = null;
            if (cacheFile.isFile() && cacheFile.length() > 0) {
                data = new JSONObject(new String(LuaToolsStore.readAllBytes(cacheFile), "UTF-8"));
            } else {
                HttpURLConnection conn = null;
                try {
                    conn = (HttpURLConnection) new URL(
                            "https://store.steampowered.com/api/appdetails?appids=" + appId + "&l=english").openConnection();
                    conn.setConnectTimeout(6000);
                    conn.setReadTimeout(8000);
                    if (conn.getResponseCode() == 200) {
                        InputStream in = conn.getInputStream();
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[8192];
                        int r;
                        while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
                        JSONObject root = new JSONObject(bos.toString("UTF-8"));
                        JSONObject appObj = root.optJSONObject(String.valueOf(appId));
                        if (appObj != null && appObj.optBoolean("success", false)) {
                            data = appObj.optJSONObject("data");
                            if (data != null) {
                                LuaToolsStore.writeAllBytes(cacheFile, data.toString().getBytes("UTF-8"));
                            }
                        }
                    }
                } finally {
                    if (conn != null) conn.disconnect();
                }
            }
            if (data != null) {
                m.description = data.optString("short_description", "");
                m.developer = joinJsonStrings(data.optJSONArray("developers"), null);
                m.publisher = joinJsonStrings(data.optJSONArray("publishers"), null);
                JSONObject rd = data.optJSONObject("release_date");
                if (rd != null) m.releaseDate = rd.optString("date", "");
                m.categories = joinJsonStrings(data.optJSONArray("genres"), "description");
                m.tags = joinJsonStrings(data.optJSONArray("categories"), "description");
                m.langs = data.optString("supported_languages", "").replaceAll("<[^>]*>", "");
                JSONArray ss = data.optJSONArray("screenshots");
                if (ss != null) {
                    JSONArray urls = new JSONArray();
                    for (int i = 0; i < ss.length(); i++) {
                        JSONObject s = ss.optJSONObject(i);
                        if (s != null) {
                            String p = s.optString("path_full", "");
                            if (!p.isEmpty()) urls.put(p);
                        }
                    }
                    m.screenshots = urls.toString();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "fetchSteamMeta warning for " + appId + ": " + t.getMessage());
        }
        return m;
    }

    private static String joinJsonStrings(JSONArray arr, String objKey) {
        if (arr == null || arr.length() == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            String val = objKey == null ? arr.optString(i, "") : (arr.optJSONObject(i) != null ? arr.optJSONObject(i).optString(objKey, "") : "");
            if (!val.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(val);
            }
        }
        return sb.toString();
    }

    public static boolean removeFromLibrary(Context ctx, int appId) {
        if (ctx == null || appId <= 0) return false;
        String rowId = gameRowId(appId);
        File dbFile = ctx.getDatabasePath(DB_NAME);
        if (dbFile == null || !dbFile.exists()) return false;

        if (removeViaBundledDriver(dbFile, rowId)) return true;
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            db.beginTransaction();
            db.execSQL("DELETE FROM t_game_launch_method WHERE linked_game_id=?", new Object[]{rowId});
            db.execSQL("DELETE FROM t_game_library_base WHERE id=?", new Object[]{rowId});
            db.setTransactionSuccessful();
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "removeFromLibrary failed", t);
            return false;
        } finally {
            if (db != null) {
                try { if (db.inTransaction()) db.endTransaction(); } catch (Throwable ignored) {}
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean registerViaBundledDriver(
            File dbFile, String rowId, int appId, String name, String coverUrl, String exePath, SteamMeta meta
    ) {
        Object conn = null;
        try {
            Class<?> connCls = Class.forName(CONNECTION_IFACE);
            Class<?> stmtCls = Class.forName(STATEMENT_IFACE);
            Object driver = Class.forName(DRIVER_CLASS).getDeclaredConstructor().newInstance();
            conn = driver.getClass().getMethod("open", String.class).invoke(driver, dbFile.getPath());

            String userId = FALLBACK_USER_ID;
            int extType = FALLBACK_EXT_TYPE;
            Object qStmt = prepare(conn, connCls,
                    "SELECT extension_type, user_id FROM t_game_library_base WHERE id <> ? LIMIT 1");
            try {
                bindText(qStmt, stmtCls, 1, rowId);
                if (step(qStmt, stmtCls)) {
                    extType = (int) getLong(qStmt, stmtCls, 0);
                    String u = getText(qStmt, stmtCls, 1);
                    if (u != null && !u.isEmpty()) userId = u;
                }
            } finally {
                closeStmt(qStmt, stmtCls);
            }

            String extData = buildExtensionData(rowId, appId, name, coverUrl, exePath);
            String appStr = String.valueOf(appId);

            execSql(conn, connCls, stmtCls, "BEGIN IMMEDIATE");
            try {
                String legacyRowId = "lua_" + appId;
                execSql1(conn, connCls, stmtCls, "DELETE FROM t_game_launch_method WHERE linked_game_id = ?", rowId);
                execSql1(conn, connCls, stmtCls, "DELETE FROM t_game_library_base WHERE id = ?", rowId);
                execSql1(conn, connCls, stmtCls, "DELETE FROM t_game_launch_method WHERE linked_game_id = ?", legacyRowId);
                execSql1(conn, connCls, stmtCls, "DELETE FROM t_game_library_base WHERE id = ?", legacyRowId);

                Object insLm = prepare(conn, connCls,
                        "INSERT INTO t_game_launch_method (linked_game_id, start_type, start_name, extension_data) VALUES (?, ?, ?, ?)");
                try {
                    bindText(insLm, stmtCls, 1, rowId);
                    bindLong(insLm, stmtCls, 2, START_TYPE_PC_EMU);
                    bindText(insLm, stmtCls, 3, name);
                    bindText(insLm, stmtCls, 4, extData);
                    step(insLm, stmtCls);
                } finally {
                    closeStmt(insLm, stmtCls);
                }

                long lmId = 0L;
                Object rowIdStmt = prepare(conn, connCls, "SELECT last_insert_rowid()");
                try {
                    if (step(rowIdStmt, stmtCls)) lmId = getLong(rowIdStmt, stmtCls, 0);
                } finally {
                    closeStmt(rowIdStmt, stmtCls);
                }

                Object insBase = prepare(conn, connCls,
                        "INSERT INTO t_game_library_base "
                                + "(id, user_id, server_game_id, steam_app_id, extension_type, launch_method_id, "
                                + "game_name, game_source, source_type, `from`, source_id, "
                                + "cover_image, cover_ver_image, logo, icon_url, square_image, back_image, "
                                + "description, developer, publisher, company, release_date, "
                                + "game_category, game_tag, game_lang, screenshot, platforms) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
                try {
                    bindText(insBase, stmtCls, 1, rowId);
                    bindText(insBase, stmtCls, 2, userId);
                    bindLong(insBase, stmtCls, 3, appId);
                    bindText(insBase, stmtCls, 4, appStr);
                    bindLong(insBase, stmtCls, 5, extType);
                    bindLong(insBase, stmtCls, 6, lmId);
                    bindText(insBase, stmtCls, 7, name);
                    bindLong(insBase, stmtCls, 8, 1);
                    bindLong(insBase, stmtCls, 9, 1);
                    bindLong(insBase, stmtCls, 10, 0);
                    bindText(insBase, stmtCls, 11, appStr);
                    bindText(insBase, stmtCls, 12, steamHeaderUrl(appId));
                    bindText(insBase, stmtCls, 13, coverUrl);
                    bindText(insBase, stmtCls, 14, steamLogoUrl(appId));
                    bindText(insBase, stmtCls, 15, steamHeaderUrl(appId));
                    bindText(insBase, stmtCls, 16, steamHeaderUrl(appId));
                    bindText(insBase, stmtCls, 17, steamHeroUrl(appId));
                    bindText(insBase, stmtCls, 18, meta.description);
                    bindText(insBase, stmtCls, 19, meta.developer);
                    bindText(insBase, stmtCls, 20, meta.publisher);
                    bindText(insBase, stmtCls, 21, meta.publisher);
                    bindText(insBase, stmtCls, 22, meta.releaseDate);
                    bindText(insBase, stmtCls, 23, meta.categories);
                    bindText(insBase, stmtCls, 24, meta.tags);
                    bindText(insBase, stmtCls, 25, meta.langs);
                    bindText(insBase, stmtCls, 26, meta.screenshots);
                    bindText(insBase, stmtCls, 27, "windows");
                    step(insBase, stmtCls);
                } finally {
                    closeStmt(insBase, stmtCls);
                }

                execSql(conn, connCls, stmtCls, "COMMIT");
                Log.i(TAG, "Registered " + rowId + " via BundledSQLiteDriver (lm=" + lmId + " user=" + userId + ")");
                return true;
            } catch (Throwable t) {
                try { execSql(conn, connCls, stmtCls, "ROLLBACK"); } catch (Throwable ignored) {}
                throw t;
            }
        } catch (Throwable t) {
            Log.w(TAG, "registerViaBundledDriver failed, falling back: " + t.getMessage());
            return false;
        } finally {
            if (conn != null) {
                try { conn.getClass().getMethod("close").invoke(conn); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean removeViaBundledDriver(File dbFile, String rowId) {
        Object conn = null;
        try {
            Class<?> connCls = Class.forName(CONNECTION_IFACE);
            Class<?> stmtCls = Class.forName(STATEMENT_IFACE);
            Object driver = Class.forName(DRIVER_CLASS).getDeclaredConstructor().newInstance();
            conn = driver.getClass().getMethod("open", String.class).invoke(driver, dbFile.getPath());
            execSql(conn, connCls, stmtCls, "BEGIN IMMEDIATE");
            try {
                String legacyRowId = "lua_" + appId;
                execSql1(conn, connCls, stmtCls, "DELETE FROM t_game_launch_method WHERE linked_game_id = ?", rowId);
                execSql1(conn, connCls, stmtCls, "DELETE FROM t_game_library_base WHERE id = ?", rowId);
                execSql1(conn, connCls, stmtCls, "DELETE FROM t_game_launch_method WHERE linked_game_id = ?", legacyRowId);
                execSql1(conn, connCls, stmtCls, "DELETE FROM t_game_library_base WHERE id = ?", legacyRowId);
                execSql(conn, connCls, stmtCls, "COMMIT");
                return true;
            } catch (Throwable t) {
                try { execSql(conn, connCls, stmtCls, "ROLLBACK"); } catch (Throwable ignored) {}
                throw t;
            }
        } catch (Throwable t) {
            return false;
        } finally {
            if (conn != null) {
                try { conn.getClass().getMethod("close").invoke(conn); } catch (Throwable ignored) {}
            }
        }
    }

    private static boolean registerViaPlatformSqlite(
            File dbFile, String rowId, int appId, String name, String coverUrl, String exePath, SteamMeta meta
    ) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READWRITE);
            String userId = FALLBACK_USER_ID;
            int extType = FALLBACK_EXT_TYPE;
            try (Cursor c = db.rawQuery(
                    "SELECT extension_type, user_id FROM t_game_library_base WHERE id<>? LIMIT 1",
                    new String[]{rowId})) {
                if (c.moveToFirst()) {
                    extType = c.getInt(0);
                    String u = c.getString(1);
                    if (u != null && !u.isEmpty()) userId = u;
                }
            }
            String extData = buildExtensionData(rowId, appId, name, coverUrl, exePath);
            String appStr = String.valueOf(appId);

            String legacyRowId = "lua_" + appId;
            db.beginTransaction();
            db.execSQL("DELETE FROM t_game_launch_method WHERE linked_game_id=?", new Object[]{rowId});
            db.execSQL("DELETE FROM t_game_library_base WHERE id=?", new Object[]{rowId});
            db.execSQL("DELETE FROM t_game_launch_method WHERE linked_game_id=?", new Object[]{legacyRowId});
            db.execSQL("DELETE FROM t_game_library_base WHERE id=?", new Object[]{legacyRowId});
            db.execSQL(
                    "INSERT INTO t_game_launch_method (linked_game_id,start_type,start_name,extension_data) VALUES (?,?,?,?)",
                    new Object[]{rowId, START_TYPE_PC_EMU, name, extData});
            long lmId;
            try (Cursor c = db.rawQuery("SELECT last_insert_rowid()", null)) {
                c.moveToFirst();
                lmId = c.getLong(0);
            }
            db.execSQL(
                    "INSERT INTO t_game_library_base "
                            + "(id,user_id,server_game_id,steam_app_id,extension_type,launch_method_id,"
                            + "game_name,game_source,source_type,`from`,source_id,"
                            + "cover_image,cover_ver_image,logo,icon_url,square_image,back_image,"
                            + "description,developer,publisher,company,release_date,"
                            + "game_category,game_tag,game_lang,screenshot,platforms) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    new Object[]{rowId, userId, appId, appStr, extType, lmId,
                            name, 1, 1, 0, appStr,
                            steamHeaderUrl(appId), coverUrl, steamLogoUrl(appId),
                            steamHeaderUrl(appId), steamHeaderUrl(appId), steamHeroUrl(appId),
                            meta.description, meta.developer, meta.publisher, meta.publisher, meta.releaseDate,
                            meta.categories, meta.tags, meta.langs, meta.screenshots, "windows"});
            db.setTransactionSuccessful();
            Log.i(TAG, "Registered " + rowId + " via platform SQLite (lm=" + lmId + ")");
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "registerViaPlatformSqlite failed", t);
            return false;
        } finally {
            if (db != null) {
                try { if (db.inTransaction()) db.endTransaction(); } catch (Throwable ignored) {}
                try { db.close(); } catch (Throwable ignored) {}
            }
        }
    }

    private static String buildExtensionData(String rowId, int appId, String name, String coverUrl, String exePath) {
        try {
            JSONObject o = new JSONObject();
            o.put("gameId", rowId);
            o.put("isLocalGame", true);
            o.put("coverImage", coverUrl);
            o.put("name", name);
            o.put("startType", START_TYPE_PC_EMU);
            o.put("steamAppId", appId);
            o.put("gogId", "");
            o.put("exePath", exePath);
            return o.toString();
        } catch (Throwable t) {
            return "{}";
        }
    }

    // ── BundledSQLiteDriver reflection helpers ──
    private static Object prepare(Object conn, Class<?> connCls, String sql) throws Exception {
        Method m = connCls.getMethod("prepare", String.class);
        return m.invoke(conn, sql);
    }

    private static boolean step(Object stmt, Class<?> stmtCls) throws Exception {
        return Boolean.TRUE.equals(stmtCls.getMethod("step").invoke(stmt));
    }

    private static void bindText(Object stmt, Class<?> stmtCls, int idx, String val) throws Exception {
        stmtCls.getMethod("bindText", int.class, String.class).invoke(stmt, idx, val);
    }

    private static void bindLong(Object stmt, Class<?> stmtCls, int idx, long val) throws Exception {
        stmtCls.getMethod("bindLong", int.class, long.class).invoke(stmt, idx, val);
    }

    private static long getLong(Object stmt, Class<?> stmtCls, int idx) throws Exception {
        return ((Number) stmtCls.getMethod("getLong", int.class).invoke(stmt, idx)).longValue();
    }

    private static String getText(Object stmt, Class<?> stmtCls, int idx) throws Exception {
        Object o = stmtCls.getMethod("getText", int.class).invoke(stmt, idx);
        return o != null ? o.toString() : null;
    }

    private static void closeStmt(Object stmt, Class<?> stmtCls) {
        if (stmt != null) {
            try { stmtCls.getMethod("close").invoke(stmt); } catch (Throwable ignored) {}
        }
    }

    private static void execSql(Object conn, Class<?> connCls, Class<?> stmtCls, String sql) throws Exception {
        Object s = prepare(conn, connCls, sql);
        try { step(s, stmtCls); } finally { closeStmt(s, stmtCls); }
    }

    private static void execSql1(Object conn, Class<?> connCls, Class<?> stmtCls, String sql, String arg1) throws Exception {
        Object s = prepare(conn, connCls, sql);
        try {
            bindText(s, stmtCls, 1, arg1);
            step(s, stmtCls);
        } finally {
            closeStmt(s, stmtCls);
        }
    }
}
