package com.termux.app;

import com.termux.R;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.app.agentui.AgentHeader;
import com.termux.app.agentui.AgentNav;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Files screen: the real contents of the Termux home directory. */
public final class AgentFilesActivity extends AgentBaseActivity {

    private static final String[] MEDIA_EXT = {
        ".jpg", ".jpeg", ".png", ".gif", ".webp", ".mp4", ".mkv", ".webm", ".mp3", ".wav"
    };

    private AgentHeader mHeader;
    private ListView mListView;
    private TextView mEmpty;
    private TextView mTabFiles;
    private TextView mTabMedia;
    private final List<File> mFiles = new ArrayList<>();
    private ArrayAdapter<File> mAdapter;
    private boolean mMediaOnly;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_agent_files);

        mHeader = new AgentHeader(this);
        mHeader.setOnMenuClick(v -> AgentNav.wireMenuBack(this));
        mHeader.setOnSettingsClick(v -> startActivity(new Intent(this, AgentSettingsActivity.class)));
        mHeader.setOnNewSessionClick(v -> startActivity(new Intent(this, AgentTerminalActivity.class)));

        mEmpty = findViewById(R.id.file_empty);
        mListView = findViewById(R.id.file_list);
        mAdapter = new ArrayAdapter<File>(this, R.layout.item_file_row, mFiles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView != null ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_file_row, parent, false);
                File f = getItem(position);
                ((TextView) v.findViewById(R.id.file_name)).setText(f.getName());
                ((TextView) v.findViewById(R.id.file_size)).setText(
                    f.isDirectory() ? "Folder" : humanSize(f.length()));
                return v;
            }
        };
        mListView.setAdapter(mAdapter);
        mListView.setOnItemClickListener((parent, view, position, id) -> showFileDetails(mFiles.get(position)));

        mTabFiles = findViewById(R.id.files_tab_files);
        mTabMedia = findViewById(R.id.files_tab_media);
        mTabFiles.setOnClickListener(v -> setMediaOnly(false));
        mTabMedia.setOnClickListener(v -> setMediaOnly(true));

        AgentNav.wire(this, AgentNav.Screen.FILES, null);
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

    private void setMediaOnly(boolean mediaOnly) {
        mMediaOnly = mediaOnly;
        mTabFiles.setTextColor(getColor(mediaOnly ? R.color.agent_text_secondary : R.color.agent_text));
        mTabMedia.setTextColor(getColor(mediaOnly ? R.color.agent_text : R.color.agent_text_secondary));
        refresh();
    }

    private void refresh() {
        mFiles.clear();
        File home = new File(TermuxConstants.TERMUX_HOME_DIR_PATH);
        File[] listed = home.listFiles();
        if (listed != null) {
            Arrays.sort(listed, (a, b) -> {
                if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });
            for (File f : listed) {
                if (mMediaOnly && !isMedia(f)) continue;
                mFiles.add(f);
            }
        }
        mEmpty.setVisibility(mFiles.isEmpty() ? View.VISIBLE : View.GONE);
        mAdapter.notifyDataSetChanged();
    }

    private boolean isMedia(File f) {
        if (f.isDirectory()) return false;
        String name = f.getName().toLowerCase(Locale.ROOT);
        for (String ext : MEDIA_EXT) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private void showFileDetails(final File f) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
            .setTitle(f.getName())
            .setMessage((f.isDirectory() ? "Folder" : humanSize(f.length())) + "\n" + f.getAbsolutePath())
            .setPositiveButton("Terminal kholo", (d, w) -> startActivity(new Intent(this, AgentTerminalActivity.class)))
            .setNegativeButton("Band", null);
        if (!f.isDirectory() && isTextFile(f)) {
            builder.setNeutralButton("Content dekho", (d, w) -> showFileContent(f));
        }
        builder.show();
    }

    private static boolean isTextFile(File f) {
        String name = f.getName().toLowerCase(Locale.ROOT);
        String[] exts = {".md", ".txt", ".log", ".sh", ".json", ".properties",
            ".py", ".js", ".xml", ".yaml", ".yml", ".toml", ".env"};
        for (String ext : exts) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    /** Shows the start of a text file (e.g. crash_log.md) in a scrollable dialog. */
    private void showFileContent(final File f) {
        new Thread(() -> {
            String content;
            try {
                byte[] buffer = new byte[20000];
                int read;
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    read = in.read(buffer);
                }
                if (read <= 0) {
                    content = "(khaali file)";
                } else {
                    content = new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8);
                    if (f.length() > read) content += "\n\n... (file aur badi hai)";
                }
            } catch (Exception e) {
                content = "File padh nahi paya: " + e.getMessage();
            }
            final String text = content;
            runOnUiThread(() -> {
                TextView view = new TextView(this);
                view.setText(text);
                view.setTextSize(12);
                view.setPadding(48, 24, 48, 24);
                view.setTextIsSelectable(true);
                android.widget.ScrollView scroll = new android.widget.ScrollView(this);
                scroll.addView(view);
                new AlertDialog.Builder(this)
                    .setTitle(f.getName())
                    .setView(scroll)
                    .setPositiveButton("Band", null)
                    .show();
            });
        }).start();
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        DecimalFormat df = new DecimalFormat("0.#");
        double kb = bytes / 1024.0;
        if (kb < 1024) return df.format(kb) + " KB";
        double mb = kb / 1024.0;
        if (mb < 1024) return df.format(mb) + " MB";
        return df.format(mb / 1024.0) + " GB";
    }
}
