package com.termux.app;

import com.termux.R;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.app.activities.SettingsActivity;
import com.termux.app.agentui.AgentHeader;
import com.termux.app.agentui.AgentNav;
import com.termux.app.agentui.AgentStore;
import com.termux.app.agentui.ChatAdapter;
import com.termux.app.agentui.ChatMessage;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * The launcher chat screen: floating live-avatar header, clean message bubbles and
 * cards, composer, bottom nav and the side drawer. Chat input is written to the
 * current agent's real terminal session; terminal output never appears here.
 */
public final class AgentChatActivity extends AgentBaseActivity implements ChatAdapter.CardActionListener {

    public static final String EXTRA_OPEN_DRAWER = "open_drawer";

    private static final String[] DRAWER_ITEMS = {
        "New chat", "Main chat", "Feed", "Ideas", "Goals", "Library",
        "Agents", "Sessions", "Terminal", "Connect", "Settings"
    };

    private AgentHeader mHeader;
    private DrawerLayout mDrawer;
    private ListView mListView;
    private EditText mInput;
    private ImageButton mActionButton;
    private View mSuggestions;

    private final List<ChatMessage> mMessages = new ArrayList<>();
    private ChatAdapter mAdapter;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    private boolean mSuggestionsShown;
    private long mWorkingSince;

    private final Runnable mWorkTick = new Runnable() {
        @Override
        public void run() {
            updateWorkingState();
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (AgentStore.getKeepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        setContentView(R.layout.activity_agent_chat);

        mHeader = new AgentHeader(this);
        registerAgentHeader(mHeader);
        mHeader.setAgentName(AgentStore.getCurrentAgent(this));

        mDrawer = findViewById(R.id.chat_drawer);
        ListView drawerList = findViewById(R.id.drawer_list);
        ArrayAdapter<String> drawerAdapter = new ArrayAdapter<String>(this, R.layout.item_drawer_row, DRAWER_ITEMS) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView v = (TextView) super.getView(position, convertView, parent);
                if (position == 0) {
                    v.setTextColor(getColor(R.color.agent_accent));
                    v.setTypeface(null, android.graphics.Typeface.BOLD);
                }
                return v;
            }
        };
        drawerList.setAdapter(drawerAdapter);
        drawerList.setOnItemClickListener((parent, view, position, id) -> onDrawerItem(position));

        mHeader.setOnMenuClick(v -> mDrawer.openDrawer(GravityCompat.START));
        mHeader.setOnNewSessionClick(v -> openTerminalWithNewSession());
        mHeader.setOnSettingsClick(v -> startActivity(new Intent(this, AgentSettingsActivity.class)));
        mHeader.setOnNameClick(v -> startActivity(new Intent(this, AgentListActivity.class)));

        mListView = findViewById(R.id.chat_list);
        mAdapter = new ChatAdapter(this, mMessages, this);
        mListView.setAdapter(mAdapter);

        mSuggestions = findViewById(R.id.chat_suggestions);
        buildSuggestionChips();

        mInput = findViewById(R.id.chat_input);
        mActionButton = findViewById(R.id.chat_action);
        mInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                boolean hasText = s != null && s.toString().trim().length() > 0;
                mActionButton.setImageResource(hasText ? R.drawable.ic_agent_send : R.drawable.ic_agent_mic);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            sendCurrentInput();
            return true;
        });
        mActionButton.setOnClickListener(v -> {
            if (mInput.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, "Voice abhi nahi hai — likh kar bhejo", Toast.LENGTH_SHORT).show();
            } else {
                sendCurrentInput();
            }
        });
        findViewById(R.id.chat_plus).setOnClickListener(v -> onPlus());

        AgentNav.wire(this, AgentNav.Screen.CHAT, null);

        if (getIntent() != null && getIntent().getBooleanExtra(EXTRA_OPEN_DRAWER, false)) {
            mDrawer.openDrawer(GravityCompat.START);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra(EXTRA_OPEN_DRAWER, false) && mDrawer != null) {
            mDrawer.openDrawer(GravityCompat.START);
        }
    }

    @Override
    protected void onDestroy() {
        mHandler.removeCallbacks(mWorkTick);
        if (mHeader != null) mHeader.release();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        // Screen foreground me nahi to working-tick bhi band (battery, Step 16);
        // header video ka pause base class sambhalti hai.
        mHandler.removeCallbacks(mWorkTick);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWorkingSince != 0) mHandler.postDelayed(mWorkTick, 4000);
    }

    @Override
    protected void onServiceReady() {
        // Chat stays clean: no setup/status cards are posted here. First-run
        // setup runs silently inside the session; the approved design keeps
        // the chat empty until the user actually talks to an agent.
        ensureBootstrapAndSession(this::refreshIdleStatus);
    }

    // ---------- chat flow ----------

    private void buildSuggestionChips() {
        LinearLayout row = findViewById(R.id.chat_suggestions_row);
        String[] labels = {
            getString(R.string.agent_suggestion_files),
            getString(R.string.agent_suggestion_script),
            getString(R.string.agent_suggestion_check)
        };
        float density = getResources().getDisplayMetrics().density;
        for (String label : labels) {
            TextView chip = new TextView(this);
            chip.setText(label);
            chip.setTextSize(14);
            chip.setTextColor(getColor(R.color.agent_text));
            chip.setBackgroundResource(R.drawable.bg_agent_pill);
            int h = (int) (16 * density), v = (int) (8 * density);
            chip.setPadding(h, v, h, v);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd((int) (8 * density));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(x -> sendMessage(label));
            row.addView(chip);
        }
    }

    private void sendCurrentInput() {
        String text = mInput.getText().toString().trim();
        if (text.isEmpty()) return;
        mInput.setText("");
        sendMessage(text);
    }

    private void sendMessage(String text) {
        addMessage(ChatMessage.user(text));
        if (!mSuggestionsShown) {
            mSuggestionsShown = true;
            mSuggestions.setVisibility(View.VISIBLE);
        }
        if (mTermuxService == null) return;
        TermuxSession target = chatTargetSession();
        if (target == null) {
            ensureBootstrapAndSession(() -> {
                TermuxSession t = chatTargetSession();
                if (t != null) {
                    writeToSession(t, text + "\n");
                    beginWorking();
                }
            });
        } else {
            writeToSession(target, text + "\n");
            beginWorking();
        }
    }

    @Nullable
    private TermuxSession chatTargetSession() {
        if (mTermuxService == null) return null;
        TermuxSession named = findSessionByName(AgentStore.getCurrentAgent(this));
        if (named != null) return named;
        List<TermuxSession> all = mTermuxService.getTermuxSessions();
        return all.isEmpty() ? null : all.get(all.size() - 1);
    }

    private void addMessage(ChatMessage message) {
        mMessages.add(message);
        mAdapter.notifyDataSetChanged();
        mListView.setSelection(mMessages.size() - 1);
    }

    // ---------- working state (live avatar + status line) ----------

    private void beginWorking() {
        mWorkingSince = System.currentTimeMillis();
        mHeader.setWorking(true);
        mHeader.setStatus("Thinking");
        mHandler.removeCallbacks(mWorkTick);
        mHandler.postDelayed(mWorkTick, 4000);
    }

    private void updateWorkingState() {
        if (mWorkingSince == 0) return;
        TermuxSession target = chatTargetSession();
        long elapsed = (System.currentTimeMillis() - mWorkingSince) / 1000;
        if (target != null && !isSessionRunning(target)) {
            mWorkingSince = 0;
            mHeader.setWorking(false);
            mHeader.setStatus(null);
            addMessage(ChatMessage.result("Session band ho gaya", "Agent ruk gaya hai", null));
            return;
        }
        if (elapsed >= 120) {
            mWorkingSince = 0;
            mHeader.setWorking(false);
            refreshIdleStatus();
            return;
        }
        if (elapsed < 20) mHeader.setStatus("Thinking");
        else if (elapsed < 45) mHeader.setStatus("Checking files");
        else if (elapsed < 80) mHeader.setStatus("Working on it");
        else mHeader.setStatus("Writing reply");
        mHandler.postDelayed(mWorkTick, 4000);
    }

    private void refreshIdleStatus() {
        if (mWorkingSince != 0) return;
        mHeader.setWorking(false);
        mHeader.setStatus(null);
    }

    // ---------- actions ----------

    private void onPlus() {
        if (mTermuxService == null) return;
        ensureBootstrapAndSession(() -> {
            if (sessionLimitReached()) {
                Toast.makeText(this, "Session limit poori ho gayi hai", Toast.LENGTH_SHORT).show();
                return;
            }
            TermuxSession created = createSession(null);
            if (created != null) {
                addMessage(ChatMessage.result("Naya session ban gaya", "Terminal me khula milega", "Terminal kholo"));
            }
        });
    }

    private void openTerminalWithNewSession() {
        if (mTermuxService == null) {
            startActivity(new Intent(this, AgentTerminalActivity.class));
            return;
        }
        ensureBootstrapAndSession(() -> {
            createSession(null);
            startActivity(new Intent(this, AgentTerminalActivity.class));
        });
    }

    private void openTerminalForCurrentSession() {
        TermuxSession target = chatTargetSession();
        if (target != null && target.getTerminalSession() != null) {
            TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(this);
            if (prefs != null) prefs.setCurrentSession(target.getTerminalSession().mHandle);
        }
        startActivity(new Intent(this, AgentTerminalActivity.class));
    }

    @Override
    public void onCardAction(ChatMessage message, String action) {
        if ("result".equals(action)) {
            openTerminalForCurrentSession();
        }
    }

    private void onDrawerItem(int position) {
        mDrawer.closeDrawer(GravityCompat.START);
        switch (DRAWER_ITEMS[position]) {
            case "New chat":
                mMessages.clear();
                mAdapter.notifyDataSetChanged();
                break;
            case "Main chat":
                break;
            case "Feed":
                startActivity(AgentSimpleActivity.intent(this, AgentSimpleActivity.MODE_FEED));
                break;
            case "Ideas":
                startActivity(AgentSimpleActivity.intent(this, AgentSimpleActivity.MODE_IDEAS));
                break;
            case "Goals":
                startActivity(AgentSimpleActivity.intent(this, AgentSimpleActivity.MODE_GOALS));
                break;
            case "Library":
                startActivity(new Intent(this, AgentFilesActivity.class));
                break;
            case "Agents":
                startActivity(new Intent(this, AgentListActivity.class));
                break;
            case "Sessions":
                startActivity(new Intent(this, AgentSessionsActivity.class));
                break;
            case "Terminal":
                openTerminalForCurrentSession();
                break;
            case "Connect":
                startActivity(new Intent(this, AgentConnectActivity.class));
                break;
            case "Settings":
                startActivity(new Intent(this, AgentSettingsActivity.class));
                break;
            default:
                break;
        }
    }
}
