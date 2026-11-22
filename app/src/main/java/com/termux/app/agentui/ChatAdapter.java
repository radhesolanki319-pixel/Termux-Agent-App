package com.termux.app.agentui;

import android.content.Context;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.termux.app.R;

import java.util.List;

/** Renders chat bubbles and clean GenUI-style cards. Pure UI — no service calls. */
public final class ChatAdapter extends BaseAdapter {

    public interface CardActionListener {
        void onCardAction(ChatMessage message, String action);
    }

    private final Context mContext;
    private final LayoutInflater mInflater;
    private final List<ChatMessage> mMessages;
    private final CardActionListener mListener;

    public ChatAdapter(Context context, List<ChatMessage> messages, CardActionListener listener) {
        mContext = context;
        mInflater = LayoutInflater.from(context);
        mMessages = messages;
        mListener = listener;
    }

    @Override
    public int getCount() {
        return mMessages.size();
    }

    @Override
    public ChatMessage getItem(int position) {
        return mMessages.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @Override
    public int getViewTypeCount() {
        return ChatMessage.Type.values().length;
    }

    @Override
    public int getItemViewType(int position) {
        return mMessages.get(position).type.ordinal();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        ChatMessage msg = getItem(position);
        switch (msg.type) {
            case TEXT_USER:
                return bindText(convertView, parent, R.layout.item_chat_user, msg);
            case TEXT_ASSISTANT:
                return bindText(convertView, parent, R.layout.item_chat_assistant, msg);
            case CARD_CHECKLIST:
                return bindChecklist(convertView, parent, msg);
            case CARD_FILE:
                return bindFile(convertView, parent, msg);
            case CARD_RESULT:
            default:
                return bindResult(convertView, parent, msg);
        }
    }

    private View bindText(View convertView, ViewGroup parent, int layout, ChatMessage msg) {
        View v = convertView != null ? convertView : mInflater.inflate(layout, parent, false);
        TextView text = v.findViewById(R.id.chat_text);
        text.setText(msg.text);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, AgentStore.chatTextSp(mContext));
        return v;
    }

    private View bindChecklist(View convertView, ViewGroup parent, ChatMessage msg) {
        View v = convertView != null ? convertView : mInflater.inflate(R.layout.item_chat_checklist, parent, false);
        LinearLayout rows = v.findViewById(R.id.card_rows);
        rows.removeAllViews();
        float density = mContext.getResources().getDisplayMetrics().density;
        for (int i = 0; i < msg.checkItems.size(); i++) {
            boolean done = i < msg.checkDone.size() && msg.checkDone.get(i);
            LinearLayout row = new LinearLayout(mContext);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            int pad = (int) (5 * density);
            row.setPadding(0, pad, 0, pad);

            TextView tick = new TextView(mContext);
            tick.setText(done ? "✓" : "◐");
            tick.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            tick.setTextColor(done
                ? ContextCompat.getColor(mContext, R.color.agent_success)
                : ContextCompat.getColor(mContext, R.color.agent_text_secondary));
            int tickWidth = (int) (30 * density);
            row.addView(tick, new LinearLayout.LayoutParams(tickWidth, LinearLayout.LayoutParams.WRAP_CONTENT));

            TextView label = new TextView(mContext);
            label.setText(msg.checkItems.get(i));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
            label.setTextColor(ContextCompat.getColor(mContext, R.color.agent_text));
            row.addView(label);
            rows.addView(row);
        }
        ProgressBar bar = v.findViewById(R.id.card_progress);
        if (msg.progress >= 0) {
            bar.setVisibility(View.VISIBLE);
            bar.setProgress(msg.progress);
        } else {
            bar.setVisibility(View.GONE);
        }
        return v;
    }

    private View bindFile(View convertView, ViewGroup parent, ChatMessage msg) {
        View v = convertView != null ? convertView : mInflater.inflate(R.layout.item_chat_file, parent, false);
        ((TextView) v.findViewById(R.id.card_file_name)).setText(msg.fileName);
        ((TextView) v.findViewById(R.id.card_file_size)).setText(msg.fileSize);
        Button action = v.findViewById(R.id.card_file_action);
        action.setText(msg.fileActionLabel);
        action.setOnClickListener(x -> {
            if (mListener != null) mListener.onCardAction(msg, "file");
        });
        return v;
    }

    private View bindResult(View convertView, ViewGroup parent, ChatMessage msg) {
        View v = convertView != null ? convertView : mInflater.inflate(R.layout.item_chat_result, parent, false);
        ((TextView) v.findViewById(R.id.card_result_title)).setText(msg.resultTitle);
        TextView sub = v.findViewById(R.id.card_result_sub);
        if (msg.resultSub != null && !msg.resultSub.isEmpty()) {
            sub.setVisibility(View.VISIBLE);
            sub.setText(msg.resultSub);
        } else {
            sub.setVisibility(View.GONE);
        }
        Button action = v.findViewById(R.id.card_result_action);
        if (msg.resultActionLabel != null && !msg.resultActionLabel.isEmpty()) {
            action.setVisibility(View.VISIBLE);
            action.setText(msg.resultActionLabel);
            action.setTextColor(Color.WHITE);
            action.setOnClickListener(x -> {
                if (mListener != null) mListener.onCardAction(msg, "result");
            });
        } else {
            action.setVisibility(View.GONE);
        }
        return v;
    }
}
