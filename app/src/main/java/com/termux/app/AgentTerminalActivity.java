package com.termux.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
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
import java.io.InputStream;
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

    private TerminalSession mDisplayedSession;
    private int mFontSize;

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
        mExtraKeysView = findViewById(R.id.agent_extra_keys);

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
    protected void onDestroy() {
        restoreDisplayedSessionClient();
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
                    && handle.equals(session.getTerminalSession().mHandle)) {
                    return session;
                }
            }
        }
        return sessions.get(sessions.size() - 1);
    }

    private void showSession(@NonNull TermuxSession wrapper) {
        TerminalSession session = wrapper.getTerminalSession();
        if (session == null) {
            showEmptyState();
            return;
        }
        restoreDisplayedSessionClient();
        session.updateTerminalSessionClient(mSessionClient);
        mDisplayedSession = session;

        loadColorsAndFont();
        mTerminalView.attachSession(session);
        mTerminalView.onScreenUpdated();
        mEmptyView.setVisibility(View.GONE);
        mTerminalView.setVisibility(View.VISIBLE);

        TermuxAppSharedPreferences prefs = TermuxAppSharedPreferences.build(this);
        if (prefs != null) prefs.setCurrentSession(session.mHandle);
        mHeader.setStatus(sessionTitle(session));

        mTerminalView.requestFocus();
        KeyboardUtils.showSoftKeyboard(this, mTerminalView);
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
    }

    private static String sessionTitle(@NonNull TerminalSession session) {
        String name = session.mSessionName;
        if (name == null || name.isEmpty()) name = session.getTitle();
        return name == null || name.isEmpty() ? "Session" : name;
    }

    // ---------------------------------------------------------------- styling

    private void loadColorsAndFont() {
        try {
            File colorsFile = TermuxConstants.TERMUX_COLOR_PROPERTIES_FILE;
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
            File fontFile = TermuxConstants.TERMUX_FONT_FILE;
            Typeface typeface = (fontFile.exists() && fontFile.length() > 0)
                ? Typeface.createFromFile(fontFile) : Typeface.MONOSPACE;
            mTerminalView.setTypeface(typeface);
            mTerminalView.onScreenUpdated();
        } catch (Exception ignored) {
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
            if (changedSession == mDisplayedSession) {
                mTerminalView.onScreenUpdated();
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
