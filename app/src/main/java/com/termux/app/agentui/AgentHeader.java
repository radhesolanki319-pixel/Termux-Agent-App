package com.termux.app.agentui;

import android.app.Activity;
import android.content.res.AssetFileDescriptor;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.R;

/**
 * The shared floating header: circular LIVE avatar (video, circle-clipped) with the
 * agent name pill overlapping its bottom. While the agent works, the pill grows a
 * second line with the live status text; when idle the pill is one line (name only).
 * The avatar clip switches between the idle bust and the working pose.
 */
public final class AgentHeader implements TextureView.SurfaceTextureListener {

    private final Activity mActivity;
    private final TextureView mAvatarView;
    private final ImageView mAvatarStill;
    private final TextView mNameView;
    private final TextView mStatusView;

    private MediaPlayer mPlayer;
    private Surface mSurface;
    private String mClip = "avatar/avatar_idle.mp4";
    private boolean mWorking;
    private boolean mVideoEnabled = true;
    private boolean mPausedByHost;
    private final Object mSurfaceLock = new Object();

    public AgentHeader(Activity activity) {
        mActivity = activity;
        mAvatarView = activity.findViewById(R.id.header_avatar);
        mAvatarStill = activity.findViewById(R.id.header_avatar_still);
        mNameView = activity.findViewById(R.id.header_agent_name);
        mStatusView = activity.findViewById(R.id.header_agent_status);
        if (mAvatarStill != null) {
            mAvatarStill.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            mAvatarStill.setClipToOutline(true);
        }
        if (mAvatarView != null) {
            mAvatarView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            mAvatarView.setClipToOutline(true);
            mAvatarView.setSurfaceTextureListener(this);
        }
    }

    public void setAgentName(String name) {
        if (mNameView != null) mNameView.setText(name);
    }

    /**
     * Step 13: performance-heavy screens (the terminal) can switch the
     * header avatar from the live video to the still image — same Jolly,
     * no MediaPlayer decode and no TextureView composition cost.
     */
    public void setVideoEnabled(boolean enabled) {
        mVideoEnabled = enabled;
        if (!enabled) {
            releasePlayer();
            if (mAvatarView != null) mAvatarView.setVisibility(View.GONE);
            if (mAvatarStill != null) mAvatarStill.setVisibility(View.VISIBLE);
        } else {
            if (mAvatarStill != null) mAvatarStill.setVisibility(View.GONE);
            if (mAvatarView != null) mAvatarView.setVisibility(View.VISIBLE);
            playCurrentClip();
        }
    }

    /** Null/empty hides the status line — the pill shrinks to the name only. */
    public void setStatus(@Nullable String status) {
        if (mStatusView == null) return;
        if (status == null || status.isEmpty()) {
            mStatusView.setVisibility(View.GONE);
        } else {
            mStatusView.setText(status);
            mStatusView.setVisibility(View.VISIBLE);
        }
    }

    public void setWorking(boolean working) {
        if (mWorking == working) return;
        mWorking = working;
        mClip = working ? "avatar/avatar_working.mp4" : "avatar/avatar_idle.mp4";
        playCurrentClip();
    }

    public boolean isWorking() {
        return mWorking;
    }

    public void setOnMenuClick(View.OnClickListener listener) {
        View v = mActivity.findViewById(R.id.header_menu_btn);
        if (v != null) v.setOnClickListener(listener);
    }

    public void setOnNewSessionClick(View.OnClickListener listener) {
        View v = mActivity.findViewById(R.id.header_new_session_btn);
        if (v != null) v.setOnClickListener(listener);
    }

    public void setOnSettingsClick(View.OnClickListener listener) {
        View v = mActivity.findViewById(R.id.header_settings_btn);
        if (v != null) v.setOnClickListener(listener);
    }

    public void setOnNameClick(View.OnClickListener listener) {
        if (mNameView != null) mNameView.setOnClickListener(listener);
    }

    // ---- avatar playback ----

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int width, int height) {
        synchronized (mSurfaceLock) {
            mSurface = new Surface(surfaceTexture);
            playCurrentClipLocked();
        }
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        synchronized (mSurfaceLock) {
            releasePlayerLocked();
            if (mSurface != null) {
                mSurface.release();
                mSurface = null;
            }
        }
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
    }

    private void playCurrentClip() {
        synchronized (mSurfaceLock) {
            playCurrentClipLocked();
        }
    }

    private void playCurrentClipLocked() {
        if (!mVideoEnabled || mSurface == null) return;
        releasePlayerLocked();
        try {
            AssetFileDescriptor afd = mActivity.getAssets().openFd(mClip);
            MediaPlayer player = new MediaPlayer();
            mPlayer = player;
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            player.setSurface(mSurface);
            player.setLooping(true);
            player.setVolume(0f, 0f);
            player.setOnPreparedListener(mp -> {
                if (mp == mPlayer) mp.start();
            });
            player.setOnErrorListener((mp, what, extra) -> true);
            player.prepareAsync();
        } catch (Exception ignored) {
            // Avatar video is cosmetic; the dark circle remains if playback fails.
        }
    }

    private void releasePlayerLocked() {
        if (mPlayer != null) {
            try {
                mPlayer.stop();
            } catch (Exception ignored) {
            }
            mPlayer.release();
            mPlayer = null;
        }
        mPausedByHost = false;
    }

    private void releasePlayer() {
        synchronized (mSurfaceLock) {
            releasePlayerLocked();
        }
    }

    /**
     * Battery (Step 16): host screen foreground me nahi hai (dusri screen,
     * app background me, ya screen band) to avatar video ka decode turant
     * roko — background me faltu kuch nahi chalna chahiye. Wapas aane par
     * wahin se resume; surface khatm ho gaya ho to naya player khud banta hai.
     */
    public void onHostPause() {
        synchronized (mSurfaceLock) {
            if (mPlayer != null) {
                try {
                    if (mPlayer.isPlaying()) {
                        mPlayer.pause();
                        mPausedByHost = true;
                    }
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void onHostResume() {
        synchronized (mSurfaceLock) {
            if (!mPausedByHost) return;
            mPausedByHost = false;
            if (mPlayer != null) {
                try {
                    mPlayer.start();
                } catch (Exception ignored) {
                }
            }
        }
    }

    public void release() {
        releasePlayer();
    }
}
