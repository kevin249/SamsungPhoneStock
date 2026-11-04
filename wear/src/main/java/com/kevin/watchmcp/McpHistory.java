package com.kevin.watchmcp;

import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;

/** 服务持有消息记录，界面与后台共用同一份游标；先持久化，再发布系统提醒。 */
final class McpHistory {
    static final String STATE_KEY = "mcp_state";
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;

    McpHistory(SharedPreferences prefs) { this.prefs = prefs; }

    private JSONObject load() throws Exception {
        String saved = prefs.getString(STATE_KEY, "");
        if (!saved.isEmpty()) return new JSONObject(saved);
        JSONObject old = new JSONObject(prefs.getString("cache", "{}"));
        JSONObject state = old.optJSONObject("mcp");
        if (state == null) state = new JSONObject();
        state.put("baseline_ready", state.has("notifications"));
        state.put("seen_ids", new JSONArray(prefs.getString("mcp_seen_ids", "[]")));
        return state;
    }

    private void save(JSONObject state) throws Exception {
        if (!prefs.edit().putString(STATE_KEY, state.toString()).commit()) throw new java.io.IOException("Message storage failed");
    }

    JSONObject snapshot() {
        synchronized (LOCK) {
            try {
                JSONObject state = load();
                if (!prefs.contains(STATE_KEY)) save(state);
                return new JSONObject().put("status", state.optString("status", "尚未连接 MCP"))
                        .put("baseline_ready", state.optBoolean("baseline_ready"))
                        .put("cursor", state.optLong("cursor")).put("notifications", state.optJSONArray("notifications") == null ? new JSONArray() : state.getJSONArray("notifications"));
            } catch (Exception ignored) { return new JSONObject(); }
        }
    }

    void status(String value) {
        synchronized (LOCK) {
            try { JSONObject state = load(); state.put("status", value); save(state); }
            catch (Exception ignored) { }
        }
    }

    void merge(JSONObject payload) throws Exception {
        synchronized (LOCK) {
            JSONArray incoming = payload.optJSONArray("notifications");
            if (incoming == null) throw new java.io.IOException("Invalid notification resource");
            JSONObject state = load();
            boolean baseline = state.optBoolean("baseline_ready");
            LinkedHashMap<String, JSONObject> history = new LinkedHashMap<>();
            JSONArray previous = state.optJSONArray("notifications");
            if (previous != null) for (int i = previous.length() - 1; i >= 0; i--) {
                JSONObject row = previous.optJSONObject(i);
                if (row != null) history.put(row.optString("event_id"), row);
            }
            LinkedHashSet<String> seen = new LinkedHashSet<>();
            JSONArray savedIds = state.optJSONArray("seen_ids");
            if (savedIds != null) for (int i = 0; i < savedIds.length(); i++) seen.add(savedIds.optString(i));
            seen.addAll(history.keySet());
            JSONArray pending = state.optJSONArray("pending");
            if (pending == null) pending = new JSONArray();
            long cursor = state.optLong("cursor");
            List<JSONObject> ordered = new ArrayList<>();
            for (int i = 0; i < incoming.length(); i++) if (incoming.optJSONObject(i) != null) ordered.add(incoming.getJSONObject(i));
            ordered.sort(Comparator.comparingLong(row -> row.optLong("sequence")));
            for (JSONObject row : ordered) {
                String id = row.optString("event_id").trim();
                if (id.isEmpty()) continue;
                JSONObject signal = chartSignal(row);
                if (seen.contains(id)) {
                    JSONObject existing = history.get(id);
                    if (existing != null && signal != null) existing.put("signal", signal);
                    continue;
                }
                JSONObject clean = new JSONObject();
                for (String field : new String[]{"event_id", "title", "body", "created_at", "priority"}) clean.put(field, row.optString(field));
                clean.put("id", ++cursor);
                if (signal != null) clean.put("signal", signal);
                history.put(id, clean);
                seen.add(id);
                if (baseline) pending.put(clean);
            }
            while (seen.size() > 1000) seen.remove(seen.iterator().next());
            JSONArray retainedIds = new JSONArray();
            for (String id : seen) retainedIds.put(id);
            List<JSONObject> values = new ArrayList<>(history.values());
            JSONArray notifications = new JSONArray();
            for (int i = values.size() - 1; i >= Math.max(0, values.size() - 100); i--) notifications.put(values.get(i));
            state.put("notifications", notifications).put("seen_ids", retainedIds).put("cursor", cursor)
                    .put("pending", pending).put("baseline_ready", true).put("status", "MCP 已连接 · 后台接收中");
            save(state);
        }
    }

    private static JSONObject chartSignal(JSONObject row) throws Exception {
        if (!"trading.holding_t_signal".equals(row.optString("event_type"))) return null;
        JSONObject payload = row.optJSONObject("payload");
        if (payload == null) return null;
        String code = Watchlist.normalize(payload.optString("code"));
        String side = payload.optString("direction", payload.optString("type"));
        double price = payload.optDouble("price");
        if (code == null || !(price > 0) || !("buy_first".equals(side) || "sell_first".equals(side))) return null;
        return new JSONObject().put("symbol", code).put("time", payload.optString("bar_at"))
                .put("price", price).put("buy", "buy_first".equals(side));
    }

    List<JSONObject> pending() {
        synchronized (LOCK) {
            List<JSONObject> values = new ArrayList<>();
            try {
                JSONArray pending = load().optJSONArray("pending");
                if (pending != null) for (int i = 0; i < pending.length(); i++) if (pending.optJSONObject(i) != null) values.add(pending.getJSONObject(i));
            } catch (Exception ignored) { }
            return values;
        }
    }

    void delivered(String eventId) throws Exception {
        synchronized (LOCK) {
            JSONObject state = load();
            JSONArray previous = state.optJSONArray("pending"), retained = new JSONArray();
            if (previous != null) for (int i = 0; i < previous.length(); i++) {
                JSONObject row = previous.optJSONObject(i);
                if (row != null && !eventId.equals(row.optString("event_id"))) retained.put(row);
            }
            state.put("pending", retained);
            save(state);
        }
    }

    static void reset(SharedPreferences prefs) {
        synchronized (LOCK) { prefs.edit().remove(STATE_KEY).remove("mcp_seen_ids").remove("seen").apply(); }
    }
}
