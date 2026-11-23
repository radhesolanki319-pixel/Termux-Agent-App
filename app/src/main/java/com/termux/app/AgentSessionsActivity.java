package com.termux.app;

import com.termux.R;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.termux.app.agentui.AgentHeader;
import com.termux.app.agentui.AgentNav;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Sessions screen: the real TermuxService session list with search, rename, close. */
public final class AgentSessionsActivity extends AgentBaseActivity {

    private AgentHeader mHeader;
    private ListView mListView;
    private final List<TermuxSession> mAll = new ArrayList<>();
    private final List<TermuxSession> mShown = new ArrayList<>();
    private ArrayAdapter<TermuxSession> mAdapter;
    private String mFilter = "";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_sessions);

        mHeader = new AgentHeader(this);
        registerAgentHeader(mHeader);
        mHeader.setOnMenuClick(v -> AgentNav.wireMenuBack(this));
        mHeader.setOnSettingsClick(v -> startActivity(new Intent(this, AgentSettingsActivity.class)));
        mHeader.setOnNewSessionClick(v -> startActivity(new Intent(this, AgentTerminalActivity.class)));

        mListView = findViewById(R.id.session_list);
        mAdapter = new ArrayAdapter<TermuxSession>(this, R.layout.item_session_row, mShown) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView != null ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_session_row, parent, false);
                TermuxSession session = getItem(position);
                boolean running = isSessionRunning(session);
                v.findViewById(R.id.session_dot).setBackgroundResource(
                    running ? R.drawable.bg_agent_dot_green : R.drawable.bg_agent_dot_grey);
                String name = session.getTerminalSession() != null
                    ? session.getTerminalSession().mSessionName : null;
                if (name == null || name.isEmpty()) {
                    name = session.getTerminalSession() != null
                        ? session.getTerminalSession().getTitle() : "Session";
                }
                ((TextView) v.findViewById(R.id.session_name)).setText(name);
                ((TextView) v.findViewById(R.id.session_status)).setText(
                    running ? "Chal raha hai" : "Band hai");
                return v;
            }
        };
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener((parent, view, position, id) -> openSession(mShown.get(position)));
        mListView.setOnItemLongClickListener((parent, view, position, id) -> {
            showSessionActions(mShown.get(position));
            return true;
        });

        EditText search = findViewById(R.id.session_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                mFilter = s == null ? "" : s.toString().toLowerCase(Locale.ROOT);
                applyFilter();
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        findViewById(R.id.session_add).setOnClickListener(v -> {
            if (mTermuxService == null) return;
            ensureBootstrapAndSession(() -> {
                if (sessionLimitReached()) {
                    Toast.makeText(this, "Session limit poori ho gayi hai", Toast.LENGTH_SHORT).show();
                    return;
                }
                createSession(null);
                refresh();
            });
        });

        AgentNav.wire(this, AgentNav.Screen.NONE, null);
    }

    @Override
    protected void onServiceReady() {
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        if (mHeader != null) mHeader.release();
        super.onDestroy();
    }

    private void refresh() {
        if (mTermuxService == null) return;
        mAll.clear();
        mAll.addAll(mTermuxService.getTermuxSessions());
        applyFilter();
    }

    private void applyFilter() {
        mShown.clear();
        for (TermuxSession s : mAll) {
            if (mFilter.isEmpty() || sessionLabel(s).toLowerCase(Locale.ROOT).contains(mFilter)) {
                mShown.add(s);
            }
        }
        mAdapter.notifyDataSetChanged();
    }

    private String sessionLabel(TermuxSession session) {
        if (session.getTerminalSession() == null) return "Session";
        String name = session.getTerminalSession().mSessionName;
        if (name == null || name.isEmpty()) name = session.getTerminalSession().getTitle();
        return name == null ? "Session" : name;
    }

    private void openSession(TermuxSession session) {
        if (session.getTerminalSession() != null) {
            TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(this);
            if (prefs != null) prefs.setCurrentSession(session.getTerminalSession().mHandle);
        }
        startActivity(new Intent(this, AgentTerminalActivity.class));
    }

    private void showSessionActions(final TermuxSession session) {
        new AlertDialog.Builder(this)
            .setTitle(sessionLabel(session))
            .setItems(new String[]{"Naam badlo", "Session band karo"}, (dialog, which) -> {
                if (which == 0) {
                    final EditText input = new EditText(this);
                    input.setText(sessionLabel(session));
                    new AlertDialog.Builder(this)
                        .setTitle("Naya naam")
                        .setView(input)
                        .setPositiveButton("Save", (d, w) -> {
                            String name = input.getText().toString().trim();
                            if (!name.isEmpty() && session.getTerminalSession() != null) {
                                session.getTerminalSession().mSessionName = name;
                                refresh();
                            }
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
                } else {
                    Runnable close = () -> {
                        if (mTermuxService != null && session.getTerminalSession() != null) {
                            mTermuxService.removeTermuxSession(session.getTerminalSession());
                            refresh();
                        }
                    };
                    TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(this);
                    boolean isCurrent = prefs != null && session.getTerminalSession() != null
                        && session.getTerminalSession().mHandle != null
                        && session.getTerminalSession().mHandle.equals(prefs.getCurrentSession());
                    if (isCurrent) {
                        new AlertDialog.Builder(this)
                            .setTitle("Session band karun?")
                            .setMessage("Yeh abhi khula hua session hai.")
                            .setPositiveButton("Band karo", (d, w) -> close.run())
                            .setNegativeButton("Nahi", null)
                            .show();
                    } else {
                        close.run();
                    }
                }
            })
            .show();
    }
}
