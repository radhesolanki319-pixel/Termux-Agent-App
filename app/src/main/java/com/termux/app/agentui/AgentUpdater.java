package com.termux.app.agentui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.termux.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * In-app self update, Hermes app wala pattern: GitHub Releases par sabse
 * bada v<versionCode> tag dhoondho, installed BuildConfig.VERSION_CODE se
 * bada ho to APK download karke system installer kholo. CI har build ka
 * versionCode auto-bump karke v<code> release banata hai (workflow dekho).
 */
public final class AgentUpdater {

    private static final String RELEASES_API =
        "https://api.github.com/repos/radhesolanki319-pixel/Termux-Agent-App/releases?per_page=30";

    private AgentUpdater() {
    }

    public static final class Result {
        public final boolean updateAvailable;
        public final int versionCode;
        public final String apkUrl;
        public final String error;

        Result(boolean updateAvailable, int versionCode, String apkUrl, String error) {
            this.updateAvailable = updateAvailable;
            this.versionCode = versionCode;
            this.apkUrl = apkUrl;
            this.error = error;
        }
    }

    public interface Callback {
        void onResult(Result result);
    }

    public static void check(Callback callback) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Result result;
            try {
                result = doCheck();
            } catch (Exception e) {
                result = new Result(false, 0, null, String.valueOf(e.getMessage()));
            }
            Result finalResult = result;
            main.post(() -> callback.onResult(finalResult));
        }, "agent-update-check").start();
    }

    private static Result doCheck() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(RELEASES_API).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestProperty("User-Agent", "TermuxAgentApp");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        if (conn.getResponseCode() != 200) {
            return new Result(false, 0, null, "GitHub se jawab nahi aaya (" + conn.getResponseCode() + ")");
        }
        StringBuilder sb = new StringBuilder();
        try (InputStream in = conn.getInputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, "UTF-8"));
        }
        JSONArray releases = new JSONArray(sb.toString());
        int bestCode = BuildConfig.VERSION_CODE;
        String bestUrl = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject rel = releases.getJSONObject(i);
            String tag = rel.optString("tag_name", "");
            if (!tag.matches("v\\d+")) continue;
            int code;
            try {
                code = Integer.parseInt(tag.substring(1));
            } catch (NumberFormatException e) {
                continue;
            }
            if (code <= bestCode) continue;
            JSONArray assets = rel.optJSONArray("assets");
            if (assets == null) continue;
            for (int j = 0; j < assets.length(); j++) {
                JSONObject asset = assets.getJSONObject(j);
                String name = asset.optString("name", "");
                if (name.endsWith(".apk")) {
                    bestCode = code;
                    bestUrl = asset.optString("browser_download_url", null);
                    break;
                }
            }
        }
        if (bestUrl != null) return new Result(true, bestCode, bestUrl, null);
        return new Result(false, BuildConfig.VERSION_CODE, null, null);
    }

    /** APK download (progress ke saath) + system installer. */
    public static void downloadAndInstall(Activity activity, String apkUrl) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
            && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                .setTitle("Ek baar permission do")
                .setMessage("Naya version install karne ke liye Android ko is app ko "
                    + "\"unknown apps install\" ki ijazat chahiye. Settings me jaakar "
                    + "allow karo, phir wapas aakar Update dabao.")
                .setPositiveButton("Settings kholo", (d, w) -> {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(intent);
                })
                .setNegativeButton("Baad me", null)
                .show();
            return;
        }
        ProgressDialog dialog = new ProgressDialog(activity);
        dialog.setTitle("Update download ho raha hai");
        dialog.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        dialog.setMax(100);
        dialog.setCancelable(false);
        dialog.show();
        new Thread(() -> {
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.isDirectory()) dir.mkdirs();
                File apk = new File(dir, "Termux-Agent-App.apk");
                HttpURLConnection conn = (HttpURLConnection) new URL(apkUrl).openConnection();
                conn.setConnectTimeout(20000);
                conn.setReadTimeout(30000);
                conn.setRequestProperty("User-Agent", "TermuxAgentApp");
                long total = conn.getContentLengthLong();
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[16384];
                    long done = 0;
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        out.write(buf, 0, n);
                        done += n;
                        if (total > 0) {
                            int pct = (int) (done * 100 / total);
                            activity.runOnUiThread(() -> dialog.setProgress(pct));
                        }
                    }
                }
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    openInstaller(activity, apk);
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    dialog.dismiss();
                    Toast.makeText(activity, "Download fail: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }, "agent-update-download").start();
    }

    private static void openInstaller(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(activity, "Installer nahi khula: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
