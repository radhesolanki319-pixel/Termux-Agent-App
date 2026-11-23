package com.termux.app.agentui;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.termux.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Small local storage for the Agent UI layer: agents, goals, ideas, feed, connect, settings. */
public final class AgentStore {

    private static final String PREFS = "agent_ui";

    private AgentStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------- agents ----------

    public static final class AgentDef {
        public final String name;
        public final String command;
        public final boolean builtIn;

        public AgentDef(String name, String command, boolean builtIn) {
            this.name = name;
            this.command = command;
            this.builtIn = builtIn;
        }
    }

    public static List<AgentDef> getAgents(Context ctx) {
        List<AgentDef> out = new ArrayList<>();
        out.add(new AgentDef("Claude", "claude", true));
        out.add(new AgentDef("Hermes", "hermes", true));
        out.add(new AgentDef("Antigravity", "antigravity", true));
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString("custom_agents", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new AgentDef(o.getString("name"), o.optString("command", ""), false));
            }
        } catch (Exception e) {
            if (BuildConfig.DEBUG) Log.w("AgentStore", "Failed to parse custom_agents", e);
        }
        return out;
    }

    public static void addCustomAgent(Context ctx, String name, String command) {
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString("custom_agents", "[]"));
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("command", command);
            arr.put(o);
            prefs(ctx).edit().putString("custom_agents", arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static void removeCustomAgent(Context ctx, String name) {
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString("custom_agents", "[]"));
            JSONArray kept = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                if (!o.getString("name").equals(name)) kept.put(o);
            }
            prefs(ctx).edit().putString("custom_agents", kept.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static String getCurrentAgent(Context ctx) {
        return prefs(ctx).getString("current_agent", "Claude");
    }

    public static void setCurrentAgent(Context ctx, String name) {
        prefs(ctx).edit().putString("current_agent", name).apply();
    }

    // ---------- simple lists (goals / ideas / feed) ----------

    public static final class Entry {
        public String text;
        public String sub;
        public boolean done;

        public Entry(String text, String sub, boolean done) {
            this.text = text;
            this.sub = sub;
            this.done = done;
        }
    }

    public static List<Entry> getEntries(Context ctx, String key) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs(ctx).getString(key, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(new Entry(o.getString("text"), o.optString("sub", ""), o.optBoolean("done", false)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static void saveEntries(Context ctx, String key, List<Entry> entries) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : entries) {
                JSONObject o = new JSONObject();
                o.put("text", e.text);
                o.put("sub", e.sub);
                o.put("done", e.done);
                arr.put(o);
            }
            prefs(ctx).edit().putString(key, arr.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    // ---------- connect ----------

    public static String getProvider(Context ctx) {
        return prefs(ctx).getString("provider", "OpenRouter");
    }

    public static void setProvider(Context ctx, String provider) {
        prefs(ctx).edit().putString("provider", provider).apply();
    }

    public static String getApiKey(Context ctx) {
        return prefs(ctx).getString("api_key", "");
    }

    public static void setApiKey(Context ctx, String key) {
        prefs(ctx).edit().putString("api_key", key).apply();
    }

    // ---------- settings ----------

    /** 0 = Small, 1 = Medium, 2 = Large. */
    public static int getFontSizeIndex(Context ctx) {
        return prefs(ctx).getInt("font_size", 1);
    }

    public static void setFontSizeIndex(Context ctx, int index) {
        prefs(ctx).edit().putInt("font_size", index).apply();
    }

    public static float chatTextSp(Context ctx) {
        switch (getFontSizeIndex(ctx)) {
            case 0:
                return 14f;
            case 2:
                return 18f;
            default:
                return 16f;
        }
    }

    public static boolean getKeepScreenOn(Context ctx) {
        return prefs(ctx).getBoolean("keep_screen_on", false);
    }

    public static void setKeepScreenOn(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean("keep_screen_on", on).apply();
    }
}
