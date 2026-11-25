package app.revanced.extension.gamehub.luatools;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.luatools.javasteam.LuaToolsJavaSteamHook;
import com.luatools.javasteam.LuaToolsSteamEmu;
import com.luatools.javasteam.LuaToolsStore;

import org.json.JSONObject;

import java.io.File;
import java.util.List;
import java.util.Map;

import app.revanced.extension.gamehub.gog.BhDownloadService;

/**
 * In-app LuaTools Steam Manager for BannerHub v6.
 * Opened via Banner Tools -> "LuaTools".
 * Displays all .lua games pushed by LuaTools Android, lets the user
 * download/verify depots via SteamPipe CDN, register into db_game_library.db,
 * trigger CloudRedirect save sync, or uninstall.
 */
public class BhLuaGamesActivity extends Activity implements BhDownloadService.GlobalListener {

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout listLayout;
    private TextView statusBanner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        refreshList();
    }

    @Override
    protected void onResume() {
        super.onResume();
        BhDownloadService.addGlobalListener(this);
        refreshList();
    }

    @Override
    protected void onPause() {
        BhDownloadService.removeGlobalListener(this);
        super.onPause();
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF10131A);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("LuaTools Games");
        title.setTextColor(0xFFF3F6FC);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        title.setLayoutParams(tlp);
        header.addView(title);

        Button refreshBtn = makeBtn("Refresh", 0xFF232B3A, v -> refreshList());
        header.addView(refreshBtn);

        Button closeBtn = makeBtn("Close", 0xFF2A2129, v -> finish());
        LinearLayout.LayoutParams clp = (LinearLayout.LayoutParams) closeBtn.getLayoutParams();
        clp.leftMargin = dp(8);
        header.addView(closeBtn);

        root.addView(header);

        statusBanner = new TextView(this);
        statusBanner.setTextColor(0xFF9AA7BD);
        statusBanner.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams sbp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sbp.topMargin = dp(6);
        sbp.bottomMargin = dp(10);
        statusBanner.setLayoutParams(sbp);
        root.addView(statusBanner);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        listLayout = new LinearLayout(this);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listLayout);
        root.addView(scroll);

        setContentView(root);
    }

    private void refreshList() {
        if (listLayout == null) return;
        listLayout.removeAllViews();

        LuaToolsStore store = LuaToolsJavaSteamHook.get(this).getStore();
        List<Integer> appIds = store.listLuaAppIds();
        boolean companion = BhLuaCloudHook.isCompanionInstalled(this);
        statusBanner.setText(appIds.size() + " game(s) configured  •  LuaTools Companion: "
                + (companion ? "Installed" : "Not detected")
                + "  •  SteamEmu: " + LuaToolsSteamEmu.mode(this).toUpperCase());

        if (appIds.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No .lua games pushed yet.\nOpen LuaTools Android, pick a game, and send it to BannerHub v6.");
            empty.setTextColor(0xFF9AA7BD);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            empty.setPadding(dp(12), dp(24), dp(12), dp(24));
            listLayout.addView(empty);
            return;
        }

        for (int appId : appIds) {
            listLayout.addView(buildGameCard(store, appId));
        }
    }

    private View buildGameCard(LuaToolsStore store, int appId) {
        String dlKey = "steam_lua_" + appId;
        String name = store.getGameName(appId);
        Map<Integer, Long> manifests = store.getWindowsDepotManifests(appId);
        int totalDepots = manifests.size();
        int cachedManifests = 0;
        for (Map.Entry<Integer, Long> e : manifests.entrySet()) {
            if (store.getManifestFile(e.getKey(), e.getValue()) != null) cachedManifests++;
        }

        File installDir = SteamCdnDownloader.resolveGameInstallDir(this, appId, name);
        JSONObject meta = BhLuaLibraryHelper.loadGameMeta(this, appId);
        String exePath = meta != null ? meta.optString("exe_path", "") : "";
        if (exePath.isEmpty() && installDir.isDirectory()) {
            String rel = SteamCdnDownloader.detectExecutable(this, appId, name, installDir, null);
            if (rel != null) exePath = new File(installDir, rel).getAbsolutePath();
        }
        boolean installed = !exePath.isEmpty() && new File(exePath).isFile();
        boolean active = BhDownloadService.isActive(dlKey);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF181D27);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0xFF283142);
        card.setBackground(bg);
        int pad = dp(12);
        card.setPadding(pad, pad, pad, pad);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(10);
        card.setLayoutParams(clp);

        TextView titleTv = new TextView(this);
        titleTv.setText(name + "  (" + appId + ")");
        titleTv.setTextColor(0xFFF3F6FC);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(titleTv);

        File fixMarker = new File(getFilesDir(), "luatools/fixes/" + appId + ".json");
        StringBuilder sub = new StringBuilder();
        sub.append("Manifests: ").append(cachedManifests).append("/").append(totalDepots);
        sub.append("  •  ").append(installed ? "Installed" : "Not downloaded");
        if (fixMarker.isFile()) sub.append("  •  Fix applied");
        if (active) {
            sub.append("\n").append(BhDownloadService.getLastMsg(dlKey))
                    .append(" (").append(BhDownloadService.getLastPct(dlKey)).append("%)");
        }

        TextView subTv = new TextView(this);
        subTv.setText(sub.toString());
        subTv.setTextColor(installed ? 0xFF7CE38B : 0xFF9AA7BD);
        subTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(4);
        subTv.setLayoutParams(slp);
        card.addView(subTv);

        if (active) {
            ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            pb.setMax(100);
            pb.setProgress(BhDownloadService.getLastPct(dlKey));
            LinearLayout.LayoutParams pblp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            pblp.topMargin = dp(6);
            pb.setLayoutParams(pblp);
            card.addView(pb);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        row.setLayoutParams(rlp);

        if (active) {
            row.addView(makeBtn("Cancel", 0xFF7A2E35, v -> {
                BhDownloadService.cancel(this, dlKey);
                ui.postDelayed(this::refreshList, 300);
            }));
        } else {
            String dlLabel = installed ? "Verify" : "Download";
            Button dlBtn = makeBtn(dlLabel, 0xFF265A8E, v -> {
                if (cachedManifests == 0) {
                    Toast.makeText(this, "No cached .manifest — push again from LuaTools", Toast.LENGTH_SHORT).show();
                    return;
                }
                BhLuaBridgeProvider.startGameDownload(this, appId, name);
                ui.postDelayed(this::refreshList, 250);
            });
            row.addView(dlBtn);
        }

        if (installed) {
            final String fExe = exePath;
            Button libBtn = makeBtn("Add to Library", 0xFF266248, v -> {
                try {
                    LuaToolsSteamEmu.attachGameRuntime(this, appId, installDir);
                } catch (Throwable ignored) {}
                String cover = BhLuaLibraryHelper.steamCapsuleUrl(appId);
                BhLuaLibraryHelper.saveGameMeta(this, appId, name, installDir.getAbsolutePath(), fExe, cover, true);
                boolean ok = BhLuaLibraryHelper.registerInLibrary(this, appId, name, fExe, cover);
                Toast.makeText(this, ok
                        ? "Added \"" + name + "\" — restart BannerHub to refresh library tile"
                        : "Could not register in db_game_library.db — open main Library tab once first",
                        Toast.LENGTH_LONG).show();
            });
            ((LinearLayout.LayoutParams) libBtn.getLayoutParams()).leftMargin = dp(6);
            row.addView(libBtn);

            Button cloudBtn = makeBtn("Cloud", 0xFF3E355B, v -> showCloudDialog(appId, name));
            ((LinearLayout.LayoutParams) cloudBtn.getLayoutParams()).leftMargin = dp(6);
            row.addView(cloudBtn);
        }

        Button rmBtn = makeBtn("Remove", 0xFF47272B, v -> confirmRemove(appId, name, installDir));
        ((LinearLayout.LayoutParams) rmBtn.getLayoutParams()).leftMargin = dp(6);
        row.addView(rmBtn);

        card.addView(row);
        return card;
    }

    private void showCloudDialog(int appId, String name) {
        String prefixDir = new File(getFilesDir(), "usr/home/virtual_containers/" + BhLuaLibraryHelper.gameRowId(appId))
                .getAbsolutePath();
        new AlertDialog.Builder(this)
                .setTitle("CloudRedirect — " + name)
                .setMessage("Sync saves for AppID " + appId + "?\nPrefix: " + prefixDir)
                .setPositiveButton("Pull Saves", (d, w) -> runAsync(() -> {
                    BhLuaCloudHook.onPreLaunch(this, appId, prefixDir);
                    ui.post(() -> Toast.makeText(this, "Cloud saves pulled", Toast.LENGTH_SHORT).show());
                }))
                .setNeutralButton("Push Saves", (d, w) -> runAsync(() -> {
                    BhLuaCloudHook.onPostExit(this, appId, prefixDir);
                    ui.post(() -> Toast.makeText(this, "Cloud saves pushed", Toast.LENGTH_SHORT).show());
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmRemove(int appId, String name, File installDir) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + name + "?")
                .setMessage("Deletes .lua, cached manifests, downloaded files, and library tile.")
                .setPositiveButton("Remove", (d, w) -> runAsync(() -> {
                    File lua = new File(getFilesDir(), "Steam/config/stplug-in/" + appId + ".lua");
                    if (lua.isFile()) lua.delete();
                    File acf = new File(getFilesDir(), "Steam/steamapps/appmanifest_" + appId + ".acf");
                    if (acf.isFile()) acf.delete();
                    if (installDir != null && installDir.exists()) deleteRecursive(installDir);
                    BhLuaLibraryHelper.deleteGameMeta(this, appId);
                    BhLuaLibraryHelper.removeFromLibrary(this, appId);
                    ui.post(this::refreshList);
                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void deleteRecursive(File f) {
        if (f.isDirectory()) {
            File[] cs = f.listFiles();
            if (cs != null) for (File c : cs) deleteRecursive(c);
        }
        f.delete();
    }

    private void runAsync(Runnable r) {
        Thread t = new Thread(r, "bh-lua-ui-op");
        t.setDaemon(true);
        t.start();
    }

    private Button makeBtn(String label, int color, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        b.setAllCaps(false);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(6));
        b.setBackground(bg);
        int h = dp(10);
        int v = dp(6);
        b.setPadding(h, v, h, v);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setOnClickListener(l);
        return b;
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    public void onAnyProgress(String gameId, String gameName, String msg, int pct) {
        if (gameId != null && gameId.startsWith("steam_lua_")) {
            ui.post(this::refreshList);
        }
    }

    @Override
    public void onAnyComplete(String gameId, String gameName) {
        if (gameId != null && gameId.startsWith("steam_lua_")) {
            ui.post(() -> {
                Toast.makeText(this, gameName + " installed — restart BannerHub to see tile", Toast.LENGTH_LONG).show();
                refreshList();
            });
        }
    }

    @Override
    public void onAnyError(String gameId, String msg) {
        if (gameId != null && gameId.startsWith("steam_lua_")) {
            ui.post(() -> {
                Toast.makeText(this, "Download failed: " + msg, Toast.LENGTH_LONG).show();
                refreshList();
            });
        }
    }

    @Override
    public void onAnyCancelled(String gameId) {
        if (gameId != null && gameId.startsWith("steam_lua_")) {
            ui.post(this::refreshList);
        }
    }
}
