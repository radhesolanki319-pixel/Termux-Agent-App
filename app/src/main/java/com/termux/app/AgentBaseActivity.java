package com.termux.app;

import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.termux.BuildConfig;
import com.termux.app.agentui.AgentHeader;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

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
    private AgentHeader mAgentHeader;

    /** Har Agent screen apna header yahan register karti hai taaki base class
     *  screen ke pause/resume par avatar video rok/saka sake (battery, Step 16). */
    protected final void registerAgentHeader(AgentHeader header) {
        mAgentHeader = header;
    }

    @Override
    protected void onPause() {
        if (mAgentHeader != null) mAgentHeader.onHostPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mAgentHeader != null) mAgentHeader.onHostResume();
    }

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
        // Har Agent screen par (chat, terminal, agents...) — sirf chat par
        // bharosa nahi: app aksar seedha terminal screen par resume hota hai.
        applyDpkgWrapperFix();
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
            applyDpkgWrapperFix();
            if (mTermuxService != null && mTermuxService.isTermuxSessionsEmpty() && !isFinishing()) {
                mTermuxService.createTermuxSession(null, null, null,
                    getDefaultWorkingDirectory(), false, null);
            }
            whenReady.run();
        });
    }

    /**
     * dpkg wrapper migration (DPKG_FIX_REPORT.md): upstream .deb packages carry
     * literal /data/data/com.termux paths, so stock dpkg cannot install anything
     * under this app's package name. New bootstraps ship bin/dpkg as a wrapper
     * script (real binary at bin/dpkg-real), but a prefix extracted by an older
     * build still has the raw ELF at bin/dpkg — this idempotent fix repairs such
     * prefixes in place from the bundled assets: an ELF at bin/dpkg is
     * stream-copied to bin/dpkg-real (no delete/rename — that path proved
     * unreliable on-device) and the wrapper is written into bin/dpkg in
     * place; the outcome is verified, and a failure is reported verbatim
     * (chat card + ~/dpkg_fix_report.txt) instead of being swallowed; the pristine copies under
     * share/termux-agent, the profile.d guard and the dpkg.cfg post-invoke hook
     * are (re)installed too. Also (re)installs the 96-dpkg-hold.sh profile.d
     * one-shot, which puts the dpkg package itself on apt hold so a dpkg
     * self-upgrade can never wipe the wrapper again (the restore guard alone
     * proved insufficient on-device: it only runs in a NEW login shell, while
     * the failing apt run keeps using the stock binary). This fix runs on
     * every Agent screen's service connect, not just the chat screen, because
     * the app often resumes straight into the terminal screen. Keep
     * assets/bootstrap-fixes/* byte-identical to ci/bootstrap-extras/*.
     * Never throws: a failure here must not break the app, the first-run
     * setup simply retries on a later launch.
     */
    /** Aakhri dpkg-fix ki report; bin/dpkg healthy wrapper ho to null. */
    static volatile String sLastDpkgFixReport;

    /** Process-wide lock for dpkg fix — prevents races when multiple screens
     *  hit onServiceConnected simultaneously (e.g., app resumes to Terminal). */
    private static final Object sDpkgFixLock = new Object();

    protected void applyDpkgWrapperFix() {
        synchronized (sDpkgFixLock) {
            StringBuilder report = new StringBuilder();
            try {
                File prefix = TermuxConstants.TERMUX_PREFIX_DIR;
                File binDir = new File(prefix, "bin");
                File dpkg = new File(binDir, "dpkg");
                if (!binDir.isDirectory() || !dpkg.isFile()) {
                    if (BuildConfig.DEBUG) Log.d("AgentBase", "dpkg fix: bootstrap not ready yet");
                    return; // bootstrap not extracted yet
                }
                File dpkgReal = new File(binDir, "dpkg-real");
                byte[] wrapperBytes = readAssetBytes("bootstrap-fixes/dpkg-wrapper.sh");
                if (isElfFile(dpkg)) {
                    // Wrapper gayab hai (dpkg self-upgrade): pehle stock binary ko
                    // streams se dpkg-real me copy karke verify karo, phir wrapper
                    // ko bin/dpkg me seedha (truncate-in-place) likho. Delete/rename
                    // par bharosa nahi — device par repair wahi atakta raha hai,
                    // aur purani chup-chaap catch wajah bhi chhupa deti thi.
                    report.append("bin/dpkg par stock binary tha (wrapper gayab)\n");
                    if (!copyFileVerified(dpkg, dpkgReal)) {
                        report.append("FAIL: stock binary dpkg-real me copy nahi hua\n");
                    } else if (!writeFileDirect(dpkg, wrapperBytes)) {
                        report.append("FAIL: bin/dpkg par wrapper nahi likha gaya\n");
                    }
                } else if (dpkgReal.isFile() && isElfFile(dpkgReal)) {
                    // Wrapper script pehle se hai: asset me nayi version ho to
                    // refresh karo (sirf tab, jab maujud file hamari hi ho).
                    byte[] current = readFileBytes(dpkg);
                    if (current != null && !Arrays.equals(current, wrapperBytes)
                        && new String(current, StandardCharsets.UTF_8).contains("Termux Agent App")) {
                        writeBytes(dpkg, wrapperBytes);
                    }
                } else {
                    report.append("FAIL: dpkg-real missing hai ya binary nahi hai\n");
                }
                dpkg.setExecutable(true, false);
                if (dpkgReal.isFile()) dpkgReal.setExecutable(true, false);
                File shareDir = new File(prefix, "share/termux-agent");
                writeBytes(new File(shareDir, "dpkg-wrapper.sh"), wrapperBytes);
                File restore = new File(shareDir, "restore-dpkg-wrapper.sh");
                writeBytes(restore, readAssetBytes("bootstrap-fixes/restore-dpkg-wrapper.sh"));
                restore.setExecutable(true, false);
                writeBytes(new File(prefix, "etc/profile.d/97-dpkg-wrapper-guard.sh"),
                    readAssetBytes("bootstrap-fixes/97-dpkg-wrapper-guard.sh"));
                writeBytes(new File(prefix, "etc/profile.d/96-dpkg-hold.sh"),
                    readAssetBytes("bootstrap-fixes/96-dpkg-hold.sh"));
                writeBytes(new File(prefix, "etc/dpkg/dpkg.cfg"),
                    ("post-invoke=\"" + TermuxConstants.TERMUX_PREFIX_DIR_PATH
                        + "/share/termux-agent/restore-dpkg-wrapper.sh\"\n")
                        .getBytes(StandardCharsets.UTF_8));
                // Step 21: Keep first-setup.sh synchronized so APK updates always carry latest resilient setup
                File agentEtcDir = new File(prefix, "etc/termux-agent");
                if (!agentEtcDir.isDirectory()) agentEtcDir.mkdirs();
                byte[] setupBytes = readAssetBytes("bootstrap-fixes/first-setup.sh");
                if (setupBytes != null && setupBytes.length > 0) {
                    File setupFile = new File(agentEtcDir, "first-setup.sh");
                    writeBytes(setupFile, setupBytes);
                    setupFile.setExecutable(true, false);
                }
                // Antim jaanch — bin/dpkg ab script (#!) hai ya nahi. Healthy ho to
                // report saaf; warna report chat card + file dono me jayegi (Step 17).
                byte[] head = readFirstBytes(dpkg, 2);
                if (head != null && head[0] == '#' && head[1] == '!') {
                    sLastDpkgFixReport = null;
                } else {
                    report.append("FAIL: antim jaanch — bin/dpkg ab bhi script nahi hai\n");
                    sLastDpkgFixReport = report.toString();
                    writeReportFile(sLastDpkgFixReport);
                }
            } catch (Exception e) {
                // Chat card me chhoti report (class + message); pura stack
                // trace sirf file me — chat me code ki deewar nahi (user rule).
                report.append("EXCEPTION: ").append(e.getClass().getSimpleName())
                    .append(": ").append(String.valueOf(e.getMessage())).append('\n');
                sLastDpkgFixReport = report.toString();
                try {
                    StringWriter sw = new StringWriter();
                    e.printStackTrace(new PrintWriter(sw));
                    writeReportFile(sLastDpkgFixReport + "\n" + sw.toString());
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static boolean isElfFile(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] magic = new byte[4];
            return in.read(magic) == 4 && magic[0] == 0x7f
                && magic[1] == 'E' && magic[2] == 'L' && magic[3] == 'F';
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean copyFile(File src, File dst) {
        try (FileInputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Copy ke baad verify bhi: destination pura ELF hai ya nahi. */
    private static boolean copyFileVerified(File src, File dst) {
        if (!copyFile(src, dst) || !dst.isFile()
                || dst.length() != src.length() || !isElfFile(dst)) {
            return false;
        }
        // Byte-for-byte jaanch. Arrays.equals ka range-wala version API 30
        // me nahi hai, isliye puri file compare karo — yeh rasta sirf tab
        // chalta hai jab wrapper gayab ho, aur binary <1MB hai.
        byte[] a = readFileBytes(src);
        byte[] b = readFileBytes(dst);
        return a != null && b != null && Arrays.equals(a, b);
    }

    /** Chhoti file ko truncate-in-place likho — koi delete/rename nahi. */
    private static boolean writeFileDirect(File target, byte[] content) {
        try (FileOutputStream out = new FileOutputStream(target, false)) {
            out.write(content);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] readFirstBytes(File file, int count) {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[count];
            if (in.read(buffer) != count) return null;
            return buffer;
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeReportFile(String text) throws Exception {
        writeBytes(new File(TermuxConstants.TERMUX_HOME_DIR_PATH, "dpkg_fix_report.txt"),
            text.getBytes(StandardCharsets.UTF_8));
        try {
            File ext = new File("/storage/emulated/0/Download/tagent_logs");
            if (ext.mkdirs() || ext.isDirectory()) {
                writeBytes(new File(ext, "dpkg_fix_report.txt"), text.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable ignored) {
        }
    }

    private static byte[] readFileBytes(File file) {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private byte[] readAssetBytes(String path) throws Exception {
        try (InputStream in = getAssets().open(path);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            return out.toByteArray();
        }
    }

    /** Writes only when the content differs, via a temp file + rename so a crash cannot leave a half-written target. */
    private static void writeBytes(File target, byte[] content) throws Exception {
        if (content == null) return;
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            if (!parent.mkdirs()) {
                throw new IOException("mkdirs failed for " + parent);
            }
        }
        if (target.isFile()) {
            try (FileInputStream in = new FileInputStream(target);
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
                if (Arrays.equals(out.toByteArray(), content)) return;
            } catch (Exception ignored) {
            }
        }
        File tmp = File.createTempFile(target.getName() + "-", "-agent", parent);
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(content);
        }
        if (target.exists() && !target.delete()) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(target);
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
