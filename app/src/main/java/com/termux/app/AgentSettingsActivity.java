package com.termux.app;

import com.termux.R;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.termux.app.activities.SettingsActivity;
import com.termux.app.agentui.AgentHeader;
import com.termux.app.agentui.AgentNav;
import com.termux.app.agentui.AgentStore;
import com.termux.app.agentui.AgentUpdater;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.Set;

/** Settings screen for the Agent UI layer. Every row is backed by something real. */
public final class AgentSettingsActivity extends AgentBaseActivity {

    private static final String[] FONT_NAMES = {"Small", "Medium", "Large"};

    private AgentHeader mHeader;
    private TextView mFontValue;
    private TextView mProviderValue;
    private TextView mStorageValue;
    private TextView mUpdateValue;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_settings);

        mHeader = new AgentHeader(this);
        registerAgentHeader(mHeader);
        mHeader.setAgentName(AgentStore.getCurrentAgent(this));
        mHeader.setOnMenuClick(v -> AgentNav.wireMenuBack(this));
        mHeader.setOnSettingsClick(v -> {
        });
        mHeader.setOnNewSessionClick(v -> startActivity(new Intent(this, AgentTerminalActivity.class)));

        mFontValue = findViewById(R.id.set_font_value);
        mProviderValue = findViewById(R.id.set_provider_value);
        mStorageValue = findViewById(R.id.set_storage_value);

        mFontValue.setText(FONT_NAMES[AgentStore.getFontSizeIndex(this)]);
        findViewById(R.id.set_row_font).setOnClickListener(v -> {
            int next = (AgentStore.getFontSizeIndex(this) + 1) % FONT_NAMES.length;
            AgentStore.setFontSizeIndex(this, next);
            mFontValue.setText(FONT_NAMES[next]);
            Toast.makeText(this, "Font size: " + FONT_NAMES[next] + " (chat me lagega)", Toast.LENGTH_SHORT).show();
        });

        Switch keepScreen = findViewById(R.id.set_keep_screen);
        keepScreen.setChecked(AgentStore.getKeepScreenOn(this));
        keepScreen.setOnCheckedChangeListener((button, checked) -> {
            AgentStore.setKeepScreenOn(this, checked);
            Toast.makeText(this, checked ? "Chat khulne par screen on rahegi" : "Screen-on band", Toast.LENGTH_SHORT).show();
        });

        mProviderValue.setText(AgentStore.getProvider(this));
        findViewById(R.id.set_row_provider).setOnClickListener(v ->
            startActivity(new Intent(this, AgentConnectActivity.class)));

        mUpdateValue = findViewById(R.id.set_update_value);
        findViewById(R.id.set_row_update).setOnClickListener(v -> checkForUpdate());

        findViewById(R.id.set_open_advanced).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));

        AgentNav.wire(this, AgentNav.Screen.MENU, null);
        computeStorageAsync();
    }

    /** Self-update check (Hermes app wala pattern): naya v<code> release ho to seedha download + installer. */
    private void checkForUpdate() {
        if (mUpdateValue != null) mUpdateValue.setText("Check ho raha hai…");
        AgentUpdater.check(result -> {
            if (isFinishing()) return;
            if (result.updateAvailable) {
                if (mUpdateValue != null) mUpdateValue.setText("Naya hai: v" + result.versionCode);
                new AlertDialog.Builder(this)
                    .setTitle("Naya update taiyaar hai")
                    .setMessage("Version v" + result.versionCode + " aa gaya hai. "
                        + "Download karke installer khol du?")
                    .setPositiveButton("Update karo", (d, w) ->
                        AgentUpdater.downloadAndInstall(this, result.apkUrl))
                    .setNegativeButton("Baad me", null)
                    .show();
            } else if (result.error != null) {
                if (mUpdateValue != null) mUpdateValue.setText("Check fail");
                Toast.makeText(this, "Update check fail: " + result.error, Toast.LENGTH_LONG).show();
            } else {
                if (mUpdateValue != null) mUpdateValue.setText("Latest hai");
                Toast.makeText(this, "Tumhara app sabse latest hai", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onServiceReady() {
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mProviderValue != null) mProviderValue.setText(AgentStore.getProvider(this));
    }

    @Override
    protected void onDestroy() {
        if (mHeader != null) mHeader.release();
        super.onDestroy();
    }

    private void computeStorageAsync() {
        new Thread(() -> {
            long home = dirSize(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
            long prefix = dirSize(new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH));
            final String label = "Home " + AgentFilesActivity.humanSize(home)
                + " • System " + AgentFilesActivity.humanSize(prefix);
            runOnUiThread(() -> {
                if (mStorageValue != null) mStorageValue.setText(label);
            });
        }).start();
    }

    private static long dirSize(File dir) {
        return dirSize(dir, new java.util.HashSet<String>());
    }
    private static long dirSize(File dir, Set<String> visited) {
        String canon;
        try {
            canon = dir.getCanonicalPath();
        } catch (Exception e) {
            return 0;
        }
        if (!visited.add(canon)) return 0; // cycle detected
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return total;
        for (File f : files) {
            if (f.isDirectory()) total += dirSize(f, visited);
            else total += f.length();
        }
        return total;
    }
}
