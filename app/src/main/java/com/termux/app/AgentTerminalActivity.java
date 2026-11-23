package com.termux.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.termux.R;
import com.termux.app.agentui.AgentHeader;
import com.termux.app.agentui.AgentNav;
import com.termux.app.agentui.AgentStore;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.terminal.TermuxTerminalViewClientBase;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.shared.view.KeyboardUtils;
import com.termux.terminal.TerminalColors;
import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalView;

import org.json.JSONException;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Terminal screen inside the new Agent UI (approved design: header + one big
 * rounded card + extra-key pills + bottom bar). The old TermuxActivity is
 * never opened from the new UI anymore — this activity shows the current
 * session itself.
 *
 * Session-client handling: TermuxService's single activity-client slot stays
 * owned by TermuxActivity and is never touched here. Instead, only the
 * session being displayed gets its TerminalSessionClient swapped to this
 * activity's client (and restored to the service's current client when the
 * display switches away or this activity is destroyed), which is the same
 * per-session update call the service itself uses.
 */
public final class AgentTerminalActivity extends AgentBaseActivity {

    private AgentHeader mHeader;
    private TerminalView mTerminalView;
    private ExtraKeysView mExtraKeysView;
    private TextView mEmptyView;
    private TextView mErrorView;

    private TerminalSession mDisplayedSession;
    private TermuxSession mPendingWrapper;
    private boolean mAttached;
    private ViewTreeObserver.OnGlobalLayoutListener mLayoutListener;
    private int mFontSize;

    // Step 12 (lag fix): repaint coalescing + colors/font reload caching.
    private boolean mRepaintPending;
    private long mColorsLoadedAt = -1;
    private long mFontLoadedAt = -1;

    private final AgentSessionClient mSessionClient = new AgentSessionClient();
    private final AgentViewClient mViewClient = new AgentViewClient();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_terminal);

        if (AgentStore.getKeepScreenOn(this)) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        mHeader = new AgentHeader(this);
        registerAgentHeader(mHeader);
        // Step 13: idle-terminal lag — the live avatar video (MediaPlayer +
        // TextureView) is a continuous decode/composition cost this screen
        // does not need; show the still Jolly here instead.
        mHeader.setVideoEnabled(false);
        mHeader.setAgentName(AgentStore.getCurrentAgent(this));
        mHeader.setOnMenuClick(v -> AgentNav.wireMenuBack(this));
        mHeader.setOnSettingsClick(v -> startActivity(new Intent(this, AgentSettingsActivity.class)));
        mHeader.setOnNewSessionClick(v -> ensureBootstrapAndSession(() -> {
            if (!sessionLimitReached()) {
                TermuxSession created = createSession(null);
                if (created != null) showSession(created);
            }
        }));

        mTerminalView = findViewById(R.id.agent_terminal_view);
        mTerminalView.setTerminalViewClient(mViewClient);
        mEmptyView = findViewById(R.id.agent_terminal_empty);
        mErrorView = findViewById(R.id.agent_terminal_error);
        mExtraKeysView = findViewById(R.id.agent_extra_keys);

        // Step 11: attach only once the view has a real (non-zero) size.
        // Attaching at 0x0 left the view with a null emulator — a permanent
        // black card — and the later size pass ran outside any guard.
        mLayoutListener = () -> {
            if (mPendingWrapper != null && !mAttached
                && mTerminalView.getWidth() > 0 && mTerminalView.getHeight() > 0) {
                doAttach(mPendingWrapper);
            }
        };
        mTerminalView.getViewTreeObserver().addOnGlobalLayoutListener(mLayoutListener);

        TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(this);
        mFontSize = prefs != null ? prefs.getFontSize() : 0;
        if (mFontSize > 0) mTerminalView.setTextSize(mFontSize);

        setupExtraKeys();

        AgentNav.wire(this, AgentNav.Screen.NONE, null);
    }

    @Override
    protected void onServiceReady() {
        ensureBootstrapAndSession(() -> {
            loadColorsAndFont();
            TermuxSession current = storedOrLastSession();
            if (current != null) {
                showSession(current);
            } else {
                showEmptyState();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mDisplayedSession != null && mTerminalView != null) {
            try {
                mTerminalView.onScreenUpdated();
            } catch (Throwable ignored) {
            }
        }
    }

    @Override
    protected void onDestroy() {
        try {
            if (mTerminalView != null && mLayoutListener != null)
                mTerminalView.getViewTreeObserver().removeOnGlobalLayoutListener(mLayoutListener);
        } catch (Throwable ignored) {
        }
        restoreDisplayedSessionClient();
        if (mHeader != null) mHeader.release();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- sessions

    @Nullable
    private TermuxSession storedOrLastSession() {
        if (mTermuxService == null) return null;
        List<TermuxSession> sessions = new ArrayList<>(mTermuxService.getTermuxSessions());
        if (sessions.isEmpty()) return null;
        TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(this);
        String handle = prefs != null ? prefs.getCurrentSession() : null;
        if (handle != null) {
            for (TermuxSession session : sessions) {
                if (session.getTerminalSession() != null
                    && session.getTerminalSession().isRunning()
                    && handle.equals(session.getTerminalSession().mHandle)) {
                    return session;
                }
            }
        }
        // Step 11: never hand out a dead session — attaching a corpse paints
        // a black card. Fall back to the last session that is still alive.
        for (int i = sessions.size() - 1; i >= 0; i--) {
            TerminalSession candidate = sessions.get(i).getTerminalSession();
            if (candidate != null && candidate.isRunning()) return sessions.get(i);
        }
        return null;
    }

    private void showSession(@NonNull TermuxSession wrapper) {
        // Step 11: defer the attach until the TerminalView has a real size;
        // the layout listener performs it on the first sized layout pass.
        mPendingWrapper = wrapper;
        mAttached = false;
        if (mTerminalView.getWidth() > 0 && mTerminalView.getHeight() > 0) {
            doAttach(wrapper);
        }
    }

    private void doAttach(@NonNull TermuxSession wrapper) {
        // Defensive: a terminal attach hiccup must never crash the app —
        // report it on this screen itself and fall back to the empty state.
        try {
            TerminalSession session = wrapper.getTerminalSession();
            if (session == null || !session.isRunning()) {
                mPendingWrapper = null;
                showEmptyState();
                return;
            }
            restoreDisplayedSessionClient();
            session.updateTerminalSessionClient(mSessionClient);
            mDisplayedSession = session;

            loadColorsAndFont();
            mTerminalView.attachSession(session);
            mTerminalView.updateSize();
            mTerminalView.onScreenUpdated();
            mEmptyView.setVisibility(View.GONE);
            mErrorView.setVisibility(View.GONE);
            mTerminalView.setVisibility(View.VISIBLE);
            // Step 13: no cursor-blinker loop and no scrollbar fade cycle on
            // this screen — the cursor still renders, steady. Both were
            // continuous main-thread work even with an idle shell.
            try {
                mTerminalView.setTerminalCursorBlinkerState(false, false);
                mTerminalView.setVerticalScrollBarEnabled(false);
            } catch (Throwable ignored) {
            }
            // Attach is done: the layout listener has no more work, remove
            // it so it stops firing on every layout pass of this window.
            try {
                if (mLayoutListener != null)
                    mTerminalView.getViewTreeObserver().removeOnGlobalLayoutListener(mLayoutListener);
            } catch (Throwable ignored) {
            }

            TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(this);
            if (prefs != null) prefs.setCurrentSession(session.mHandle);
            mHeader.setStatus(sessionTitle(session));

            mTerminalView.requestFocus();
            KeyboardUtils.showSoftKeyboard(this, mTerminalView);
            mAttached = true;
            mPendingWrapper = null;
        } catch (Throwable t) {
            reportTerminalError(t);
            showEmptyState();
        }
    }

    /**
     * Step 11 self-report: a terminal failure is written to
     * ~/terminal_error.txt and shown inside the terminal card itself, so the
     * exact cause is one screenshot away instead of buried in app-private
     * storage the user cannot reach from the phone's file manager.
     */
    private void reportTerminalError(@NonNull Throwable t) {
        final String trace;
        try {
            StringWriter sw = new StringWriter();
            t.printStackTrace(new PrintWriter(sw));
            trace = sw.toString();
        } catch (Throwable ignored) {
            return;
        }
        new Thread(() -> {
            try {
                File out = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "terminal_error.txt");
                try (FileOutputStream fos = new FileOutputStream(out, true)) {
                    fos.write(("\n==== " + new java.util.Date() + " ====\n" + trace).getBytes());
                }
            } catch (Throwable ignored) {
            }
        }).start();
        runOnUiThread(() -> {
            try {
                mTerminalView.setVisibility(View.GONE);
                mEmptyView.setVisibility(View.GONE);
                mErrorView.setText("Terminal me error aaya — is screen ka screenshot bhej do:\n\n"
                    + (trace.length() > 4000 ? trace.substring(0, 4000) : trace));
                mErrorView.setVisibility(View.VISIBLE);
            } catch (Throwable ignored) {
            }
        });
    }

    private void showEmptyState() {
        mDisplayedSession = null;
        mTerminalView.setVisibility(View.GONE);
        mEmptyView.setVisibility(View.VISIBLE);
        mHeader.setStatus(null);
    }

    private void restoreDisplayedSessionClient() {
        if (mDisplayedSession != null && mTermuxService != null) {
            try {
                mDisplayedSession.updateTerminalSessionClient(
                    mTermuxService.getTermuxTerminalSessionClient());
            } catch (Exception ignored) {
            }
        }
        mDisplayedSession = null;
    }

    private void handleSessionFinished(@NonNull TerminalSession finished) {
        try {
            if (mTermuxService == null) return;
            boolean wasDisplayed = finished == mDisplayedSession;
            if (wasDisplayed) mDisplayedSession = null;
            mTermuxService.removeTermuxSession(finished);
            if (wasDisplayed) {
                TermuxSession next = storedOrLastSession();
                if (next != null && next.getTerminalSession() != finished) {
                    showSession(next);
                } else {
                    showEmptyState();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String sessionTitle(@NonNull TerminalSession session) {
        String name = session.mSessionName;
        if (name == null || name.isEmpty()) name = session.getTitle();
        return name == null || name.isEmpty() ? "Session" : name;
    }

    // ---------------------------------------------------------------- styling

    private void loadColorsAndFont() {
        try {
            // Step 12: only re-read/re-apply when the files actually changed.
            // setTypeface() recreates the terminal renderer, so calling this
            // on every colors-changed callback was a steady source of jank.
            File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
            long colorsMod = colorsFile.isFile() ? colorsFile.lastModified() : 0;
            if (colorsMod != mColorsLoadedAt) {
                Properties props = new Properties();
                if (colorsFile.isFile()) {
                    try (InputStream in = new FileInputStream(colorsFile)) {
                        props.load(in);
                    }
                }
                TerminalColors.COLOR_SCHEME.updateWith(props);
                if (mDisplayedSession != null && mDisplayedSession.getEmulator() != null) {
                    mDisplayedSession.getEmulator().mColors.reset();
                }
                mColorsLoadedAt = colorsMod;
            }
            File fontFile = TermuxConstants.TERMUX_FONT_FILE;
            long fontMod = fontFile.exists() ? fontFile.lastModified() : 0;
            if (fontMod != mFontLoadedAt) {
                Typeface typeface = (fontFile.exists() && fontFile.length() > 0)
                    ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
                mTerminalView.setTypeface(typeface);
                mFontLoadedAt = fontMod;
            }
            mTerminalView.onScreenUpdated();
        } catch (Throwable ignored) {
        }
    }

    private void setupExtraKeys() {
        try {
            TerminalExtraKeys extraKeys = new TerminalExtraKeys(mTerminalView);
            mExtraKeysView.setExtraKeysViewClient(extraKeys);
            TermuxAppSharedProperties props = TermuxAppSharedProperties.getProperties();
            mExtraKeysView.setButtonTextAllCaps(props == null || props.shouldExtraKeysTextBeAllCaps());
            ExtraKeysInfo info = buildExtraKeysInfo(props);
            if (info != null) {
                float heightPx = 148 * getResources().getDisplayMetrics().density;
                mExtraKeysView.reload(info, heightPx);
            }
        } catch (Exception ignored) {
        }
    }

    @Nullable
    private static ExtraKeysInfo buildExtraKeysInfo(@Nullable TermuxAppSharedProperties props) {
        if (props == null) return null;
        try {
            String extraKeys = (String) props.getInternalPropertyValue(
                TermuxPropertyConstants.KEY_EXTRA_KEYS, true);
            String style = (String) props.getInternalPropertyValue(
                TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, true);
            ExtraKeysConstants.ExtraKeyDisplayMap displayMap =
                ExtraKeysInfo.getCharDisplayMapForStyle(style);
            if (ExtraKeysConstants.EXTRA_KEY_DISPLAY_MAPS.DEFAULT_CHAR_DISPLAY.equals(displayMap)
                && !TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE.equals(style)) {
                style = TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE;
            }
            return new ExtraKeysInfo(extraKeys, style, ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        } catch (JSONException e) {
            try {
                return new ExtraKeysInfo(TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
                    TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS_STYLE,
                    ExtraKeysConstants.CONTROL_CHARS_ALIASES);
            } catch (JSONException e2) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    // ---------------------------------------------------------------- clients

    private final class AgentSessionClient extends TermuxTerminalSessionClientBase {

        @Override
        public void onTextChanged(@NonNull TerminalSession changedSession) {
            try {
                // Step 12: coalesce repaints — heavy output (progress bars,
                // long listings) fires this per chunk; repaint at most once
                // per posted frame instead of once per chunk.
                if (changedSession != mDisplayedSession || mTerminalView == null
                    || mRepaintPending) return;
                mRepaintPending = true;
                mTerminalView.post(() -> {
                    mRepaintPending = false;
                    try {
                        mTerminalView.onScreenUpdated();
                    } catch (Throwable ignored) {
                    }
                });
            } catch (Throwable ignored) {
            }
        }

        @Override
        public void onTitleChanged(@NonNull TerminalSession updatedSession) {
            if (updatedSession == mDisplayedSession) {
                runOnUiThread(() -> mHeader.setStatus(sessionTitle(updatedSession)));
            }
        }

        @Override
        public void onSessionFinished(@NonNull TerminalSession finishedSession) {
            runOnUiThread(() -> handleSessionFinished(finishedSession));
        }

        @Override
        public void onCopyTextToClipboard(@NonNull TerminalSession session, String text) {
            ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("", text));
            }
        }

        @Override
        public void onPasteTextFromClipboard(@Nullable TerminalSession session) {
            ClipboardManager clipboard =
                (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null || clipboard.getPrimaryClip() == null
                || clipboard.getPrimaryClip().getItemCount() == 0) return;
            CharSequence text = clipboard.getPrimaryClip().getItemAt(0).getText();
            if (text != null && mDisplayedSession != null && mTerminalView.mEmulator != null) {
                mTerminalView.mEmulator.paste(text.toString());
            }
        }

        @Override
        public void onColorsChanged(@NonNull TerminalSession changedSession) {
            runOnUiThread(() -> loadColorsAndFont());
        }

        @Override
        public Integer getTerminalCursorStyle() {
            try {
                TermuxAppSharedProperties props = TermuxAppSharedProperties.getProperties();
                if (props != null) return props.getTerminalCursorStyle();
            } catch (Exception ignored) {
            }
            return super.getTerminalCursorStyle();
        }
    }

    private final class AgentViewClient extends TermuxTerminalViewClientBase {

        @Override
        public float onScale(float scale) {
            if (scale < 0.9f || scale > 1.1f) {
                changeFontSize(scale > 1.0f);
                return 1.0f;
            }
            return scale;
        }

        private void changeFontSize(boolean increase) {
            if (mFontSize <= 0) return;
            mFontSize = Math.max(10, Math.min(60, mFontSize + (increase ? 2 : -2)));
            mTerminalView.setTextSize(mFontSize);
        }

        @Override
        public void onSingleTapUp(android.view.MotionEvent e) {
            KeyboardUtils.showSoftKeyboard(AgentTerminalActivity.this, mTerminalView);
        }

        @Override
        public boolean isTerminalViewSelected() {
            return mDisplayedSession != null;
        }

        @Override
        public boolean readControlKey() {
            return readExtraKeysSpecialButton(SpecialButton.CTRL);
        }

        @Override
        public boolean readAltKey() {
            return readExtraKeysSpecialButton(SpecialButton.ALT);
        }

        @Override
        public boolean readShiftKey() {
            return readExtraKeysSpecialButton(SpecialButton.SHIFT);
        }

        @Override
        public boolean readFnKey() {
            return readExtraKeysSpecialButton(SpecialButton.FN);
        }

        private boolean readExtraKeysSpecialButton(SpecialButton button) {
            if (mExtraKeysView == null) return false;
            Boolean state = mExtraKeysView.readSpecialButton(button, false);
            return state != null && state;
        }
    }
}
