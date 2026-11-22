package com.termux.app;

import android.content.Intent;
import android.os.Bundle;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.termux.app.agentui.AgentHeader;
import com.termux.app.agentui.AgentNav;
import com.termux.app.agentui.AgentStore;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Connect screen: picks the provider and stores the API key. The choice is also
 * written to ~/.termux-agent/provider.env, which new terminal sessions source
 * (profile.d hook), so the agents inside use the same provider and key.
 */
public final class AgentConnectActivity extends AgentBaseActivity {

    private AgentHeader mHeader;
    private RadioGroup mProviders;
    private EditText mKeyInput;
    private TextView mStatus;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_connect);

        mHeader = new AgentHeader(this);
        mHeader.setAgentName(AgentStore.getCurrentAgent(this));
        mHeader.setOnMenuClick(v -> AgentNav.wireMenuBack(this));
        mHeader.setOnSettingsClick(v -> startActivity(new Intent(this, AgentSettingsActivity.class)));
        mHeader.setOnNewSessionClick(v -> TermuxActivity.startTermuxActivity(this));

        mProviders = findViewById(R.id.connect_providers);
        mKeyInput = findViewById(R.id.connect_key);
        mStatus = findViewById(R.id.connect_status);

        String provider = AgentStore.getProvider(this);
        if ("FreeLLM".equals(provider)) mProviders.check(R.id.connect_freellm);
        else if ("Custom".equals(provider)) mProviders.check(R.id.connect_custom);
        else mProviders.check(R.id.connect_openrouter);

        String existingKey = AgentStore.getApiKey(this);
        if (!existingKey.isEmpty()) {
            mKeyInput.setText(existingKey);
            mStatus.setText("Key pehle se save hai");
        }

        findViewById(R.id.connect_save).setOnClickListener(v -> save());

        AgentNav.wire(this, AgentNav.Screen.MENU, null);
    }

    @Override
    protected void onServiceReady() {
    }

    @Override
    protected void onDestroy() {
        if (mHeader != null) mHeader.release();
        super.onDestroy();
    }

    private String selectedProvider() {
        int id = mProviders.getCheckedRadioButtonId();
        if (id == R.id.connect_freellm) return "FreeLLM";
        if (id == R.id.connect_custom) return "Custom";
        return "OpenRouter";
    }

    private void save() {
        String provider = selectedProvider();
        String key = mKeyInput.getText().toString().trim();
        AgentStore.setProvider(this, provider);
        if (!key.isEmpty()) AgentStore.setApiKey(this, key);
        boolean envWritten = writeProviderEnv(provider, AgentStore.getApiKey(this));
        mStatus.setText(envWritten
            ? "Save ho gaya — naye sessions me yahi provider lagega"
            : "Save ho gaya");
        Toast.makeText(this, "Connect save ho gaya", Toast.LENGTH_SHORT).show();
    }

    private boolean writeProviderEnv(String provider, String key) {
        try {
            File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux-agent");
            if (!dir.exists() && !dir.mkdirs()) return false;
            String var;
            switch (provider) {
                case "FreeLLM":
                    var = "FREELLM_API_KEY";
                    break;
                case "Custom":
                    var = "CUSTOM_API_KEY";
                    break;
                default:
                    var = "OPENROUTER_API_KEY";
                    break;
            }
            String content = "# Termux Agent App — provider settings (Connect screen se)\n"
                + "export AGENT_PROVIDER=\"" + escape(provider) + "\"\n"
                + "export " + var + "=\"" + escape(key) + "\"\n";
            File out = new File(dir, "provider.env");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$");
    }
}
