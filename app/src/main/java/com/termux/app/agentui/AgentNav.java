package com.termux.app.agentui;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.widget.ImageButton;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.termux.app.AgentChatActivity;
import com.termux.app.AgentFilesActivity;
import com.termux.app.AgentSimpleActivity;
import com.termux.R;

/** Wires the shared 5-icon bottom bar: Chat, Files, Ideas, Goals, Menu (grid). */
public final class AgentNav {

    public enum Screen {CHAT, FILES, IDEAS, GOALS, MENU, NONE}

    private AgentNav() {
    }

    public static void wire(final Activity activity, final Screen current, @Nullable final Runnable menuAction) {
        ImageButton chat = activity.findViewById(R.id.nav_chat);
        ImageButton files = activity.findViewById(R.id.nav_files);
        ImageButton ideas = activity.findViewById(R.id.nav_ideas);
        ImageButton goals = activity.findViewById(R.id.nav_goals);
        ImageButton menu = activity.findViewById(R.id.nav_menu);
        if (chat == null) return;

        tint(activity, chat, current == Screen.CHAT);
        tint(activity, files, current == Screen.FILES);
        tint(activity, ideas, current == Screen.IDEAS);
        tint(activity, goals, current == Screen.GOALS);
        tint(activity, menu, current == Screen.MENU);

        chat.setOnClickListener(v -> {
            if (current != Screen.CHAT) go(activity, new Intent(activity, AgentChatActivity.class));
        });
        files.setOnClickListener(v -> {
            if (current != Screen.FILES) go(activity, new Intent(activity, AgentFilesActivity.class));
        });
        ideas.setOnClickListener(v -> {
            if (current != Screen.IDEAS)
                go(activity, AgentSimpleActivity.intent(activity, AgentSimpleActivity.MODE_IDEAS));
        });
        goals.setOnClickListener(v -> {
            if (current != Screen.GOALS)
                go(activity, AgentSimpleActivity.intent(activity, AgentSimpleActivity.MODE_GOALS));
        });
        menu.setOnClickListener(v -> {
            if (menuAction != null) {
                menuAction.run();
            } else if (current != Screen.CHAT) {
                Intent intent = new Intent(activity, AgentChatActivity.class);
                intent.putExtra(AgentChatActivity.EXTRA_OPEN_DRAWER, true);
                go(activity, intent);
            }
        });
    }

    /** Header menu button on non-chat screens: back to the chat, drawer open. */
    public static void wireMenuBack(Activity activity) {
        Intent intent = new Intent(activity, AgentChatActivity.class);
        intent.putExtra(AgentChatActivity.EXTRA_OPEN_DRAWER, true);
        go(activity, intent);
    }

    private static void tint(Activity activity, ImageButton button, boolean active) {
        int color = active ? Color.WHITE
            : ContextCompat.getColor(activity, R.color.agent_dim);
        button.setColorFilter(color);
    }

    private static void go(Activity activity, Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        activity.startActivity(intent);
    }
}
