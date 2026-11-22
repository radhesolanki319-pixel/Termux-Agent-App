package com.termux.app;

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
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

/** Settings screen for the Agent UI layer. Every row is backed by something real. */
public final class AgentSettingsActivity extends AgentBaseActivity {

    private static final String[] FONT_NAMES = {"Small", "Medium", "Large"};

    private AgentHeader mHeader;
    private TextView mFontValue;
    private TextView mProviderValue;
    private TextView mStorageValue;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_settings);

        mHeader = new AgentHeader(this);
        mHeader.setAgentName(AgentStore.getCurrentAgent(this));
        mHeader.setOnMenuClick(v -> AgentNav.wireMenuBack(this));
        mHeader.setOnSettingsClick(v -> {
        });
        mHeader.setOnNewSessionClick(v -> TermuxActivity.startTermuxActivity(this));

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

        findViewById(R.id.set_open_advanced).setOnClickListener(v ->
            startActivity(new Intent(this, SettingsActivity.class)));

        AgentNav.wire(this, AgentNav.Screen.MENU, null);
        computeStorageAsync();
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
        long total = 0;
        File[] files = dir.listFiles();
        if (files == null) return total;
        for (File f : files) {
            if (f.isDirectory()) total += dirSize(f);
            else total += f.length();
        }
        return total;
    }
}
