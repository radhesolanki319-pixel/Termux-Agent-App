package com.termux.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

import java.nio.charset.StandardCharsets;

/**
 * Base class for the chat-first Agent UI activities.
 *
 * Mirrors TermuxActivity's service pattern (start + bind, unbind on destroy) but
 * NEVER touches the single terminal session-client slot, which stays
 * owned by TermuxActivity. Sessions are used only through TermuxService's public
 * list/create/remove API.
 */
public abstract class AgentBaseActivity extends AppCompatActivity implements ServiceConnection {

    /** Mirrors the activity client MAX_SESSIONS limit (enforced there, not in the service). */
    protected static final int MAX_SESSIONS = 8;

    protected TermuxService mTermuxService;
    private boolean mServiceBound;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = new Intent(this, TermuxService.class);
        startService(intent);
        mServiceBound = bindService(intent, this, 0);
    }

    @Override
    protected void onDestroy() {
        if (mServiceBound) {
            unbindService(this);
            mServiceBound = false;
        }
        super.onDestroy();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        mTermuxService = ((TermuxService.LocalBinder) binder).service;
        onServiceReady();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        mTermuxService = null;
    }

    protected abstract void onServiceReady();

    protected String getDefaultWorkingDirectory() {
        try {
            TermuxAppSharedProperties props = TermuxAppSharedProperties.getProperties();
            if (props != null) {
                String cwd = props.getDefaultWorkingDirectory();
                if (cwd != null) return cwd;
            }
        } catch (Exception ignored) {
        }
        return TermuxConstants.TERMUX_HOME_DIR_PATH;
    }

    /**
     * Replicates TermuxActivity's first-run sequence: the bootstrap installer runs
     * first, and a session is created only inside its completion callback.
     */
    protected void ensureBootstrapAndSession(final Runnable whenReady) {
        if (mTermuxService == null) return;
        if (!mTermuxService.isTermuxSessionsEmpty()) {
            whenReady.run();
            return;
        }
        TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
            if (mTermuxService != null && mTermuxService.isTermuxSessionsEmpty() && !isFinishing()) {
                mTermuxService.createTermuxSession(null, null, null,
                    getDefaultWorkingDirectory(), false, null);
            }
            whenReady.run();
        });
    }

    @Nullable
    protected TermuxSession findSessionByName(@Nullable String name) {
        if (mTermuxService == null || name == null) return null;
        return mTermuxService.getTermuxSessionForShellName(name);
    }

    protected boolean sessionLimitReached() {
        return mTermuxService != null && mTermuxService.getTermuxSessionsSize() >= MAX_SESSIONS;
    }

    @Nullable
    protected TermuxSession createSession(@Nullable String sessionName) {
        if (mTermuxService == null || sessionLimitReached()) return null;
        return mTermuxService.createTermuxSession(null, null, null,
            getDefaultWorkingDirectory(), false, sessionName);
    }

    protected static void writeToSession(@Nullable TermuxSession session, @Nullable String text) {
        if (session == null || text == null || session.getTerminalSession() == null) return;
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        session.getTerminalSession().write(bytes, 0, bytes.length);
    }

    protected static boolean isSessionRunning(@Nullable TermuxSession session) {
        return session != null && session.getTerminalSession() != null
            && session.getTerminalSession().isRunning();
    }
}
