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
import com.termux.app.agentui.AgentUpdater;
import com.termux.app.agentui.ChatAdapter;
import com.termux.app.agentui.ChatMessage;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.terminal.TerminalSession;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The launcher chat screen: floating live-avatar header, clean message bubbles and
 * cards, composer, bottom nav and the side drawer. Chat talks to the current agent
 * DIRECTLY: each message runs the agent's one-shot command in a dedicated runner
 * session and the agent's real reply comes back as a bubble. The interactive
 * terminal is only for installing/managing agents; its output never appears here.
 */
public final class AgentChatActivity extends AgentBaseActivity implements ChatAdapter.CardActionListener {

    public static final String EXTRA_OPEN_DRAWER = "open_drawer";

    private static final String[] DRAWER_ITEMS = {
        "New chat", "Main chat", "Feed", "Ideas", "Goals", "Library",
        "Agents", "Sessions", "Terminal", "Connect", "Settings", "Check Update"
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

    // ---------- direct agent chat (runner session + reply files) ----------

    /** Dedicated shell session that only runs one-shot agent commands for chat. */
    private static final String RUNNER_SESSION = "agent-chat-run";
    private static final long REPLY_TIMEOUT_MS = 900_000; // 15 min

    private final ArrayDeque<String> mQueue = new ArrayDeque<>();
    private String mPendingId;
    private long mPendingSince;

    private final Runnable mReplyPoll = new Runnable() {
        @Override
        public void run() {
            pollReply();
        }
    };

    /**
     * Runner script (bash): runs ONE one-shot agent command and writes the reply
     * to <id>.out, then <id>.done. The message travels via a file, never through
     * the shell command line, so quotes/symbols in it cannot break anything.
     * Claude reads the prompt from stdin in -p mode and --continue keeps its
     * conversation (per-agent working dir); Hermes answers via
     * chat --query-file --oneshot with a named --continue session.
     */
    private static final String RUNNER_SCRIPT =
        "#!/data/data/com.tagent/files/usr/bin/bash\n"
        + "# Termux Agent App chat runner — chat screen isi se agent se baat karti hai.\n"
        + "AGENT=\"$1\"; ID=\"$2\"\n"
        + "DIR=\"$HOME/.termux-agent/chat\"\n"
        + "MSG=\"$DIR/$ID.msg\"; OUT=\"$DIR/$ID.out\"; DONE=\"$DIR/$ID.done\"\n"
        + "WORK=\"$DIR/work-$AGENT\"\n"
        + "mkdir -p \"$WORK\" 2>/dev/null\n"
        + "cd \"$WORK\" 2>/dev/null\n"
        + "{\n"
        + "case \"$AGENT\" in\n"
        + "  claude)\n"
        + "    if [ -x \"$PREFIX/bin/claude\" ]; then\n"
        + "      timeout 900 \"$PREFIX/bin/claude\" -p --continue < \"$MSG\"\n"
        + "    else\n"
        + "      echo \"__ERR__ Claude abhi install nahi hai. Pehle Terminal se setup pura hone do.\"\n"
        + "    fi ;;\n"
        + "  hermes)\n"
        + "    if [ -x \"$PREFIX/bin/hermes\" ]; then\n"
        + "      timeout 900 \"$PREFIX/bin/hermes\" chat --query-file \"$MSG\" --oneshot --continue agentchat\n"
        + "    else\n"
        + "      echo \"__ERR__ Hermes abhi install nahi hai. Pehle Terminal se setup pura hone do.\"\n"
        + "    fi ;;\n"
        + "  antigravity)\n"
        + "    if command -v antigravity >/dev/null 2>&1; then\n"
        + "      timeout 900 antigravity -p \"$(cat \"$MSG\")\"\n"
        + "    elif command -v agy >/dev/null 2>&1; then\n"
        + "      timeout 900 agy -p \"$(cat \"$MSG\")\"\n"
        + "    else\n"
        + "      KEY=\"${FREELLM_API_KEY:-freellmapi-c7bf396fcf0351dbba9546933a8881054c627e6f78875206}\"\n"
        + "      Q=$(cat \"$MSG\")\n"
        + "      SYSTEM=\"You are Antigravity Agent in Termux Agent App on Boss's iQOO Neo 10. Reply strictly in Roman Hinglish to Boss (Radhe Solanki). Keep cool at 34-35C, zero background daemons.\"\n"
        + "      PAYLOAD=$(jq -n --arg sys \"$SYSTEM\" --arg q \"$Q\" '{\"model\":\"auto\",\"messages\":[{\"role\":\"system\",\"content\":$sys},{\"role\":\"user\",\"content\":$q}]}' 2>/dev/null)\n"
        + "      RESP=$(curl -s --connect-timeout 10 -m 45 -X POST \"https://freellmapi-backup.onrender.com/v1/chat/completions\" \\\n"
        + "        -H \"Content-Type: application/json\" \\\n"
        + "        -H \"Authorization: Bearer $KEY\" \\\n"
        + "        -d \"$PAYLOAD\" 2>/dev/null)\n"
        + "      REPLY=$(echo \"$RESP\" | jq -r '.choices[0].message.content // empty' 2>/dev/null)\n"
        + "      if [ -n \"$REPLY\" ]; then\n"
        + "        echo \"$REPLY\"\n"
        + "      else\n"
        + "        echo \"Boss, Antigravity API se connection nahi mila. Network ya key check karo.\"\n"
        + "      fi\n"
        + "    fi ;;\n"
        + "  *)\n"
        + "    if command -v \"$AGENT\" >/dev/null 2>&1; then\n"
        + "      timeout 900 \"$AGENT\" < \"$MSG\"\n"
        + "    else\n"
        + "      echo \"__ERR__ Agent command nahi mila: $AGENT\"\n"
        + "    fi ;;\n"
        + "esac\n"
        + "} > \"$OUT\" 2>&1\n"
        + "echo done > \"$DONE\"\n";

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
        mHandler.removeCallbacks(mReplyPoll);
        if (mHeader != null) mHeader.release();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        // Screen foreground me nahi to working-tick bhi band (battery, Step 16);
        // header video ka pause base class sambhalti hai.
        mHandler.removeCallbacks(mWorkTick);
        mHandler.removeCallbacks(mReplyPoll);
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mWorkingSince != 0) mHandler.postDelayed(mWorkTick, 4000);
        if (mPendingId != null) mHandler.postDelayed(mReplyPoll, 1000);
    }

    @Override
    protected void onServiceReady() {
        // Chat stays clean: no setup/status cards are posted here. First-run
        // setup runs silently inside the session; the approved design keeps
        // the chat empty until the user actually talks to an agent.
        ensureBootstrapAndSession(() -> {
            refreshIdleStatus();
            ensureChatInfra();
        });
        // Ek hi exception (Step 17): dpkg fix fail hua ho to uski report chat
        // me khud dikhe — success par koi card nahi, chat saaf rehti hai.
        if (sLastDpkgFixReport != null) {
            addMessage(ChatMessage.result("Package fix fail ho gaya", sLastDpkgFixReport, null));
        }
        checkUpdateSilently();
    }

    // ---------- self update (chat card, process me ek hi baar) ----------

    private static boolean sUpdateCardShown;
    private ChatMessage mUpdateCard;
    private String mUpdateUrl;

    private void checkUpdateSilently() {
        if (sUpdateCardShown) return;
        sUpdateCardShown = true;
        AgentUpdater.check(result -> {
            if (isFinishing() || !result.updateAvailable) return;
            mUpdateUrl = result.apkUrl;
            mUpdateCard = ChatMessage.result("Naya update hai",
                "Version v" + result.versionCode + " taiyaar hai", "Update karo");
            addMessage(mUpdateCard);
        });
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
        // Ek samay par ek hi one-shot: jawab pending ho to message kataar me.
        if (mPendingId != null) {
            mQueue.add(text);
            return;
        }
        fireRequest(text);
    }

    /** Chat ke liye file-bridge taiyaar karo: dir + runner script (badli ho to refresh). */
    private void ensureChatInfra() {
        try {
            File dir = chatDir();
            if (!dir.isDirectory()) dir.mkdirs();
            File run = new File(dir, "run.sh");
            byte[] want = RUNNER_SCRIPT.getBytes(StandardCharsets.UTF_8);
            boolean stale = true;
            if (run.isFile() && run.length() == want.length) {
                try (FileInputStream in = new FileInputStream(run);
                     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                    stale = !java.util.Arrays.equals(out.toByteArray(), want);
                }
            }
            if (stale) {
                try (FileOutputStream out = new FileOutputStream(run)) {
                    out.write(want);
                }
            }
            run.setExecutable(true, false);
        } catch (Exception ignored) {
        }
    }

    private File chatDir() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".termux-agent/chat");
    }

    /** Current agent ka shell command — shell line me jaata hai, isliye saaf rakho. */
    private String currentAgentCommand() {
        String name = AgentStore.getCurrentAgent(this);
        String cmd = null;
        for (AgentStore.AgentDef def : AgentStore.getAgents(this)) {
            if (def.name.equals(name)) {
                cmd = def.command;
                break;
            }
        }
        if (cmd == null || cmd.isEmpty()) cmd = name.toLowerCase(java.util.Locale.US);
        cmd = cmd.trim().split("\\s+")[0];
        if (!cmd.matches("[A-Za-z0-9._-]+")) cmd = "claude";
        return cmd;
    }

    private void fireRequest(String text) {
        ensureChatInfra();
        TermuxSession runner = findSessionByName(RUNNER_SESSION);
        if (runner == null || !isSessionRunning(runner)) {
            runner = createSession(RUNNER_SESSION);
            if (runner == null) {
                addMessage(ChatMessage.result("Session nahi ban paya",
                    "Session limit pura hai — Sessions se ek purana session band karke phir bhejo", null));
                return;
            }
        }
        String id = String.valueOf(System.currentTimeMillis());
        try (FileOutputStream out = new FileOutputStream(new File(chatDir(), id + ".msg"))) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            addMessage(ChatMessage.result("Message save nahi hua", String.valueOf(e.getMessage()), null));
            return;
        }
        mPendingId = id;
        mPendingSince = System.currentTimeMillis();
        writeToSession(runner, "bash " + TermuxConstants.TERMUX_HOME_DIR_PATH
            + "/.termux-agent/chat/run.sh " + currentAgentCommand() + " " + id + "\n");
        beginWorking();
        mHandler.removeCallbacks(mReplyPoll);
        mHandler.postDelayed(mReplyPoll, 1500);
    }

    private void pollReply() {
        if (mPendingId == null) return;
        File dir = chatDir();
        if (new File(dir, mPendingId + ".done").isFile()) {
            String out = readTextFile(new File(dir, mPendingId + ".out"));
            cleanupChatFiles(mPendingId);
            mPendingId = null;
            endWorking();
            deliverReply(out);
            fireNextQueued();
            return;
        }
        if (System.currentTimeMillis() - mPendingSince > REPLY_TIMEOUT_MS) {
            cleanupChatFiles(mPendingId);
            mPendingId = null;
            endWorking();
            addMessage(ChatMessage.result("Agent jawab nahi de raha",
                "Bohot der ho gayi — Terminal me agent ki haalat dekh lo", null));
            fireNextQueued();
            return;
        }
        mHandler.postDelayed(mReplyPoll, 1500);
    }

    private void fireNextQueued() {
        String next = mQueue.poll();
        if (next != null) fireRequest(next);
    }

    /** Agent ka asli jawab bubble me — terminal ke ANSI rang saaf karke. */
    private void deliverReply(String raw) {
        String text = cleanReply(raw);
        if (text.isEmpty()) {
            addMessage(ChatMessage.assistant("(Agent ne khali jawab bheja)"));
        } else if (text.startsWith("__ERR__")) {
            addMessage(ChatMessage.result("Agent taiyaar nahi",
                text.substring("__ERR__".length()).trim(), null));
        } else {
            if (text.length() > 6000) text = text.substring(0, 6000) + "…";
            addMessage(ChatMessage.assistant(text));
        }
    }

    private static String cleanReply(@Nullable String raw) {
        if (raw == null) return "";
        String t = raw.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "");
        t = t.replaceAll("\u001B\\][^\u0007]*\u0007", "");
        t = t.replaceAll("[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]", "");
        return t.trim();
    }

    private static String readTextFile(File file) {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void cleanupChatFiles(String id) {
        File dir = chatDir();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, id + ".msg").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, id + ".out").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir, id + ".done").delete();
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

    private void endWorking() {
        mWorkingSince = 0;
        mHandler.removeCallbacks(mWorkTick);
        mHeader.setWorking(false);
        refreshIdleStatus();
    }

    private void updateWorkingState() {
        // Status shabd sirf intezaar ka ehsaas hain — working asal me tab
        // khatm hoti hai jab agent ka jawab file aa jaye (pollReply), koi
        // nakli time-cap nahi.
        if (mWorkingSince == 0) return;
        long elapsed = (System.currentTimeMillis() - mWorkingSince) / 1000;
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
        // Terminal par current agent ka interactive session kholo (install/
        // manage ke liye) — chat wala runner session kabhi target nahi.
        TermuxSession target = null;
        if (mTermuxService != null) {
            target = findSessionByName(AgentStore.getCurrentAgent(this));
            if (target == null) {
                List<TermuxSession> all = mTermuxService.getTermuxSessions();
                for (int i = all.size() - 1; i >= 0; i--) {
                    TerminalSession ts = all.get(i).getTerminalSession();
                    if (ts != null && ts.isRunning() && !RUNNER_SESSION.equals(ts.mSessionName)) {
                        target = all.get(i);
                        break;
                    }
                }
            }
        }
        if (target != null && target.getTerminalSession() != null) {
            TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(this);
            if (prefs != null) prefs.setCurrentSession(target.getTerminalSession().mHandle);
        }
        startActivity(new Intent(this, AgentTerminalActivity.class));
    }

    @Override
    public void onCardAction(ChatMessage message, String action) {
        if ("result".equals(action)) {
            if (message == mUpdateCard && mUpdateUrl != null) {
                AgentUpdater.downloadAndInstall(this, mUpdateUrl);
            } else {
                openTerminalForCurrentSession();
            }
        }
    }

    private void onDrawerItem(int position) {
        mDrawer.closeDrawer(GravityCompat.START);
        switch (DRAWER_ITEMS[position]) {
            case "New chat":
                mMessages.clear();
                mAdapter.notifyDataSetChanged();
                mPendingId = null;
                mQueue.clear();
                mHandler.removeCallbacks(mReplyPoll);
                endWorking();
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
            case "Check Update":
                AgentUpdater.check(res -> {
                    if (res.updateAvailable && res.apkUrl != null) {
                        AgentUpdater.downloadAndInstall(this, res.apkUrl);
                    } else if (res.error != null) {
                        Toast.makeText(this, "Update check error: " + res.error, Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "App up-to-date hai Boss!", Toast.LENGTH_SHORT).show();
                    }
                });
                break;
            default:
                break;
        }
    }
}
