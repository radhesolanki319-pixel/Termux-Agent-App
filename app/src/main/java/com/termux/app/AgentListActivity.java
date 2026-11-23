package com.termux.app;

import com.termux.R;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.termux.app.agentui.AgentHeader;
import com.termux.app.agentui.AgentNav;
import com.termux.app.agentui.AgentStore;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.util.ArrayList;
import java.util.List;

/** Agents screen: built-in + user-added agents with live session status. */
public final class AgentListActivity extends AgentBaseActivity {

    private AgentHeader mHeader;
    private ListView mListView;
    private final List<AgentStore.AgentDef> mAgents = new ArrayList<>();
    private ArrayAdapter<AgentStore.AgentDef> mAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_list);

        mHeader = new AgentHeader(this);
        registerAgentHeader(mHeader);
        mHeader.setAgentName(AgentStore.getCurrentAgent(this));
        mHeader.setOnMenuClick(v -> AgentNav.wireMenuBack(this));
        mHeader.setOnSettingsClick(v -> startActivity(new Intent(this, AgentSettingsActivity.class)));
        mHeader.setOnNewSessionClick(v -> startActivity(new Intent(this, AgentTerminalActivity.class)));

        ((TextView) findViewById(R.id.list_title)).setText(R.string.agent_agents);

        mListView = findViewById(R.id.agent_list);
        mAdapter = new ArrayAdapter<AgentStore.AgentDef>(this, R.layout.item_agent_row, mAgents) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView != null ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_agent_row, parent, false);
                AgentStore.AgentDef def = getItem(position);
                TermuxSession session = findSessionByName(def.name);
                boolean running = isSessionRunning(session);
                boolean exists = session != null;

                v.findViewById(R.id.agent_dot).setBackgroundResource(
                    running ? R.drawable.bg_agent_dot_green : R.drawable.bg_agent_dot_grey);
                ((TextView) v.findViewById(R.id.agent_name)).setText(def.name);
                ((TextView) v.findViewById(R.id.agent_status)).setText(
                    running ? getString(R.string.agent_status_online)
                        : exists ? getString(R.string.agent_status_stopped)
                        : getString(R.string.agent_status_offline));
                Button action = v.findViewById(R.id.agent_action);
                action.setText(running ? getString(R.string.agent_open) : getString(R.string.agent_start));
                action.setOnClickListener(x -> {
                    if (isSessionRunning(findSessionByName(def.name))) openAgent(def);
                    else startAgent(def);
                });
                return v;
            }
        };
        mListView.setAdapter(mAdapter);

        TextView footer = new TextView(this);
        footer.setText("+  Naya agent jodo");
        footer.setTextSize(16);
        footer.setTextColor(getColor(R.color.agent_accent));
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        footer.setPadding(pad, pad, pad, pad);
        footer.setOnClickListener(v -> showAddAgentDialog());
        mListView.addFooterView(footer);

        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < mAgents.size()) {
                AgentStore.AgentDef def = mAgents.get(position);
                if (!def.builtIn) {
                    new AlertDialog.Builder(this)
                        .setTitle(def.name)
                        .setMessage("Is agent ko hata dun?")
                        .setPositiveButton("Hata do", (d, w) -> {
                            AgentStore.removeCustomAgent(this, def.name);
                            reloadAgents();
                        })
                        .setNegativeButton("Nahi", null)
                        .show();
                    return true;
                }
            }
            return false;
        });

        AgentNav.wire(this, AgentNav.Screen.MENU, null);
    }

    @Override
    protected void onServiceReady() {
        reloadAgents();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadAgents();
        if (mHeader != null) mHeader.setAgentName(AgentStore.getCurrentAgent(this));
    }

    @Override
    protected void onDestroy() {
        if (mHeader != null) mHeader.release();
        super.onDestroy();
    }

    private void reloadAgents() {
        mAgents.clear();
        mAgents.addAll(AgentStore.getAgents(this));
        mAdapter.notifyDataSetChanged();
    }

    private void openAgent(AgentStore.AgentDef def) {
        AgentStore.setCurrentAgent(this, def.name);
        TermuxSession session = findSessionByName(def.name);
        if (session != null && session.getTerminalSession() != null) {
            TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(this);
            if (prefs != null) prefs.setCurrentSession(session.getTerminalSession().mHandle);
        }
        startActivity(new Intent(this, AgentTerminalActivity.class));
    }

    private void startAgent(final AgentStore.AgentDef def) {
        if (mTermuxService == null) return;
        ensureBootstrapAndSession(() -> {
            TermuxSession existing = findSessionByName(def.name);
            if (existing != null && !isSessionRunning(existing)) {
                mTermuxService.removeTermuxSession(existing.getTerminalSession());
                existing = null;
            }
            if (existing == null) {
                if (sessionLimitReached()) {
                    Toast.makeText(this, "Session limit poori ho gayi hai", Toast.LENGTH_SHORT).show();
                    return;
                }
                existing = createSession(def.name);
                if (existing == null) return;
                if (def.command != null && !def.command.trim().isEmpty()) {
                    writeToSession(existing, def.command.trim() + "\n");
                }
            }
            AgentStore.setCurrentAgent(this, def.name);
            reloadAgents();
            Intent intent = new Intent(this, AgentChatActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
            startActivity(intent);
        });
    }

    private void showAddAgentDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad, pad, 0);
        final EditText nameInput = new EditText(this);
        nameInput.setHint("Agent ka naam");
        final EditText commandInput = new EditText(this);
        commandInput.setHint("Command (jaise claude)");
        box.addView(nameInput);
        box.addView(commandInput);
        new AlertDialog.Builder(this)
            .setTitle("Naya agent")
            .setView(box)
            .setPositiveButton("Jodo", (d, w) -> {
                String name = nameInput.getText().toString().trim();
                String command = commandInput.getText().toString().trim();
                if (!name.isEmpty()) {
                    AgentStore.addCustomAgent(this, name, command);
                    reloadAgents();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}
