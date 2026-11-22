package com.termux.app;

import com.termux.R;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.termux.app.agentui.AgentHeader;
import com.termux.app.agentui.AgentNav;
import com.termux.app.agentui.AgentStore;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** One screen, three modes: Goals (checklist), Ideas (list), Feed (local posts). */
public final class AgentSimpleActivity extends AgentBaseActivity {

    public static final String MODE_GOALS = "goals";
    public static final String MODE_IDEAS = "ideas";
    public static final String MODE_FEED = "feed";

    private static final String EXTRA_MODE = "mode";

    public static Intent intent(Context ctx, String mode) {
        Intent intent = new Intent(ctx, AgentSimpleActivity.class);
        intent.putExtra(EXTRA_MODE, mode);
        return intent;
    }

    private String mMode = MODE_GOALS;
    private AgentHeader mHeader;
    private final List<AgentStore.Entry> mEntries = new ArrayList<>();
    private ArrayAdapter<AgentStore.Entry> mAdapter;
    private TextView mEmpty;

    private String storeKey() {
        return "entries_" + mMode;
    }

    private boolean isGoals() {
        return MODE_GOALS.equals(mMode);
    }

    private boolean isFeed() {
        return MODE_FEED.equals(mMode);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String mode = getIntent() != null ? getIntent().getStringExtra(EXTRA_MODE) : null;
        if (mode != null) mMode = mode;
        setContentView(R.layout.activity_agent_simple);

        mHeader = new AgentHeader(this);
        mHeader.setOnMenuClick(v -> AgentNav.wireMenuBack(this));
        mHeader.setOnSettingsClick(v -> startActivity(new Intent(this, AgentSettingsActivity.class)));
        mHeader.setOnNewSessionClick(v -> TermuxActivity.startTermuxActivity(this));

        TextView title = findViewById(R.id.simple_title);
        title.setText(isGoals() ? "Goals" : isFeed() ? "Feed" : "Ideas");

        mEmpty = findViewById(R.id.simple_empty);
        ListView list = findViewById(R.id.simple_list);
        mAdapter = new ArrayAdapter<AgentStore.Entry>(this, R.layout.item_simple_row, mEntries) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = convertView != null ? convertView
                    : LayoutInflater.from(getContext()).inflate(R.layout.item_simple_row, parent, false);
                final AgentStore.Entry entry = getItem(position);
                CheckBox check = v.findViewById(R.id.simple_check);
                TextView text = v.findViewById(R.id.simple_text);
                TextView sub = v.findViewById(R.id.simple_sub);
                TextView delete = v.findViewById(R.id.simple_delete);

                text.setText(entry.text);
                if (isGoals()) {
                    check.setVisibility(View.VISIBLE);
                    check.setOnCheckedChangeListener(null);
                    check.setChecked(entry.done);
                    check.setOnCheckedChangeListener((button, checked) -> {
                        entry.done = checked;
                        entry.sub = checked ? "Ho gaya" : "Baki hai";
                        saveAndRefresh();
                    });
                    sub.setVisibility(View.VISIBLE);
                    sub.setText(entry.done ? "Ho gaya" : "Baki hai");
                } else {
                    check.setVisibility(View.GONE);
                    if (entry.sub != null && !entry.sub.isEmpty()) {
                        sub.setVisibility(View.VISIBLE);
                        sub.setText(entry.sub);
                    } else {
                        sub.setVisibility(View.GONE);
                    }
                }
                delete.setOnClickListener(x -> {
                    mEntries.remove(entry);
                    saveAndRefresh();
                });
                return v;
            }
        };
        list.setAdapter(mAdapter);

        findViewById(R.id.simple_add).setOnClickListener(v -> {
            if (isFeed()) {
                findViewById(R.id.simple_input_row).setVisibility(View.VISIBLE);
                findViewById(R.id.simple_input).requestFocus();
            } else {
                showAddDialog();
            }
        });

        if (isFeed()) {
            findViewById(R.id.simple_input_row).setVisibility(View.VISIBLE);
            final EditText input = findViewById(R.id.simple_input);
            findViewById(R.id.simple_input_add).setOnClickListener(v -> {
                String text = input.getText().toString().trim();
                if (!text.isEmpty()) {
                    String when = new SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(new Date());
                    mEntries.add(0, new AgentStore.Entry(text, when, false));
                    input.setText("");
                    saveAndRefresh();
                }
            });
        }

        AgentNav.Screen screen = isGoals() ? AgentNav.Screen.GOALS
            : MODE_IDEAS.equals(mMode) ? AgentNav.Screen.IDEAS : AgentNav.Screen.NONE;
        AgentNav.wire(this, screen, null);
        reload();
    }

    @Override
    protected void onServiceReady() {
        // Local-data screen; nothing service-bound.
    }

    @Override
    protected void onDestroy() {
        if (mHeader != null) mHeader.release();
        super.onDestroy();
    }

    private void showAddDialog() {
        final EditText input = new EditText(this);
        input.setHint(isGoals() ? "Naya goal likho" : "Naya idea likho");
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(pad, pad / 2, pad, 0);
        new AlertDialog.Builder(this)
            .setTitle(isGoals() ? "Goal jodo" : "Idea jodo")
            .setView(input)
            .setPositiveButton("Jodo", (d, w) -> {
                String text = input.getText().toString().trim();
                if (!text.isEmpty()) {
                    mEntries.add(new AgentStore.Entry(text, isGoals() ? "Baki hai" : "", false));
                    saveAndRefresh();
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void reload() {
        mEntries.clear();
        mEntries.addAll(AgentStore.getEntries(this, storeKey()));
        mAdapter.notifyDataSetChanged();
        mEmpty.setVisibility(mEntries.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void saveAndRefresh() {
        AgentStore.saveEntries(this, storeKey(), mEntries);
        mAdapter.notifyDataSetChanged();
        mEmpty.setVisibility(mEntries.isEmpty() ? View.VISIBLE : View.GONE);
    }
}
