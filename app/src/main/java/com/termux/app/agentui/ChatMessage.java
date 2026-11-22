package com.termux.app.agentui;

import java.util.ArrayList;
import java.util.List;

/**
 * One row in the agent chat. Cards carry only clean, user-facing content —
 * never raw terminal output or command text (standing design rule).
 */
public final class ChatMessage {

    public enum Type {TEXT_USER, TEXT_ASSISTANT, CARD_CHECKLIST, CARD_FILE, CARD_RESULT}

    public final Type type;
    public String text;

    // checklist card
    public final List<String> checkItems = new ArrayList<>();
    public final List<Boolean> checkDone = new ArrayList<>();
    public int progress = -1; // -1 hides the bar

    // file card
    public String fileName;
    public String fileSize;
    public String fileActionLabel;

    // result card
    public String resultTitle;
    public String resultSub;
    public String resultActionLabel;

    private ChatMessage(Type type) {
        this.type = type;
    }

    public static ChatMessage user(String text) {
        ChatMessage m = new ChatMessage(Type.TEXT_USER);
        m.text = text;
        return m;
    }

    public static ChatMessage assistant(String text) {
        ChatMessage m = new ChatMessage(Type.TEXT_ASSISTANT);
        m.text = text;
        return m;
    }

    public static ChatMessage checklist(String[] labels, boolean[] done, int progress) {
        ChatMessage m = new ChatMessage(Type.CARD_CHECKLIST);
        for (int i = 0; i < labels.length; i++) {
            m.checkItems.add(labels[i]);
            m.checkDone.add(done != null && i < done.length && done[i]);
        }
        m.progress = progress;
        return m;
    }

    public static ChatMessage file(String name, String size, String actionLabel) {
        ChatMessage m = new ChatMessage(Type.CARD_FILE);
        m.fileName = name;
        m.fileSize = size;
        m.fileActionLabel = actionLabel;
        return m;
    }

    public static ChatMessage result(String title, String sub, String actionLabel) {
        ChatMessage m = new ChatMessage(Type.CARD_RESULT);
        m.resultTitle = title;
        m.resultSub = sub;
        m.resultActionLabel = actionLabel;
        return m;
    }
}
