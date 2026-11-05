package com.kevin.phonekline;

import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** 手机端按手表所用 Streamable HTTP MCP 协议读取最新逐笔成交。 */
final class PhoneMcpClient {
    private final String address, apiKey;
    private volatile HttpURLConnection active;
    private String version = "2026-07-28", sessionId = "";
    private long sequence;
    private volatile boolean ready;

    private final Runnable toolSent;
    PhoneMcpClient(String address, String apiKey) { this(address, apiKey, null); }
    PhoneMcpClient(String address, String apiKey, Runnable toolSent) {
        this.address = address; this.apiKey = apiKey; this.toolSent = toolSent;
    }

    JSONObject latestTrade(String symbol) throws Exception {
        return call("get_stock_active_trades", arguments(symbol).put("limit", 24));
    }

    static JSONObject arguments(String symbol) throws Exception {
        return new JSONObject().put("symbol", symbol.substring(2) + "." + symbol.substring(0, 2).toUpperCase(java.util.Locale.ROOT));
    }

    private volatile boolean cancelled;
    private String resourceUri = "";

    JSONObject notifications() throws Exception {
        try {
            if (resourceUri.isEmpty()) resourceUri = call("get_notification_stream", new JSONObject()).getString("resource_uri");
            JSONObject response = rpc("resources/read", new JSONObject().put("uri", resourceUri));
            JSONArray contents = response.getJSONArray("contents");
            for (int i = 0; i < contents.length(); i++) {
                JSONObject row = contents.getJSONObject(i);
                if (resourceUri.equals(row.optString("uri")) && row.has("text")) return new JSONObject(row.getString("text"));
            }
            throw new IOException("无通知资源");
        } catch (Exception failedRead) {
            // Both the session and the principal-scoped resource URI can rotate.
            // Rediscover on the next scheduled poll, respecting its error backoff.
            ready = false;
            resourceUri = "";
            throw failedRead;
        }
    }

    JSONObject call(String name, JSONObject args) throws Exception {
        if (!ready) negotiate();
        JSONObject result;
        try { result = rpc("tools/call", new JSONObject().put("name", name).put("arguments", args)); }
        catch (Exception failure) { ready = false; resourceUri = ""; throw failure; }
        if (result.optBoolean("isError")) throw new IOException("工具不可用");
        JSONObject structured = result.optJSONObject("structuredContent");
        if (structured != null) return structured;
        JSONArray content = result.optJSONArray("content");
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block != null && "text".equals(block.optString("type"))) return new JSONObject(block.getString("text"));
        }
        throw new IOException("MCP 工具结果为空");
    }

    private void negotiate() throws Exception {
        version = "2026-07-28"; sessionId = "";
        try { rpc("server/discover", new JSONObject()); }
        catch (RpcError error) {
            if (error.code != -32601 && error.code != -32602) throw error;
            version = "2025-11-25";
            JSONObject init = rpc("initialize", new JSONObject().put("protocolVersion", version)
                    .put("capabilities", new JSONObject()).put("clientInfo", clientInfo()));
            version = init.optString("protocolVersion", version);
            notifyInitialized();
        }
        ready = true;
    }

    private JSONObject clientInfo() throws Exception {
        return new JSONObject().put("name", "SamsungPhoneKline").put("version", "1.0");
    }

    private JSONObject request(String method, JSONObject params, long id) throws Exception {
        if ("2026-07-28".equals(version)) params.put("_meta", new JSONObject()
                .put("io.modelcontextprotocol/protocolVersion", version)
                .put("io.modelcontextprotocol/clientInfo", clientInfo())
                .put("io.modelcontextprotocol/clientCapabilities", new JSONObject()));
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params);
    }

    private JSONObject rpc(String method, JSONObject params) throws Exception {
        long id = ++sequence;
        HttpURLConnection conn = null;
        try {
            conn = open(request(method, params, id));
            JSONObject response;
            String type = conn.getContentType();
            if (type != null && type.contains("text/event-stream")) response = readEvent(conn.getInputStream(), id);
            else response = new JSONObject(readText(conn.getInputStream()));
            if (response == null || response.optLong("id", -1) != id) throw new IOException("MCP 未返回结果");
            JSONObject error = response.optJSONObject("error");
            if (error != null) throw new RpcError(error.optInt("code"));
            JSONObject result = response.optJSONObject("result");
            if (result == null) throw new IOException("MCP 结果格式不正确");
            return result;
        } finally { close(conn); }
    }

    private void notifyInitialized() throws Exception {
        HttpURLConnection conn = null;
        try { conn = open(new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized")); }
        finally { close(conn); }
    }

    private HttpURLConnection open(JSONObject payload) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        active = conn;
        try {
            if (cancelled) throw new java.io.InterruptedIOException("会话已停止");
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(6000); conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(false);
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Accept", "application/json, text/event-stream");
            conn.setRequestProperty("Mcp-Protocol-Version", version);
            if (!sessionId.isEmpty() && !"2026-07-28".equals(version)) conn.setRequestProperty("Mcp-Session-Id", sessionId);
            conn.setRequestProperty("Mcp-Method", payload.optString("method"));
            JSONObject params = payload.optJSONObject("params");
            if (params != null && ("tools/call".equals(payload.optString("method")) || "resources/read".equals(payload.optString("method")))) {
                String name = params.optString("name", params.optString("uri"));
                if (!name.matches("[\\x21-\\x7E]+")) name = "=?base64?" + Base64.encodeToString(name.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP) + "?=";
                conn.setRequestProperty("Mcp-Name", name);
            }
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setDoOutput(true);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = conn.getOutputStream()) { output.write(body); }
            if (toolSent != null && "tools/call".equals(payload.optString("method"))) toolSent.run();
            int code = conn.getResponseCode();
            String assigned = conn.getHeaderField("Mcp-Session-Id");
            if (assigned != null) sessionId = assigned;
            if (code == 401 || code == 403 || code == 429) throw new RpcError(code);
            if (code < 200 || code >= 300) {
                int rpcCode = code;
                try {
                    JSONObject error = new JSONObject(readText(conn.getErrorStream())).optJSONObject("error");
                    if (error != null) rpcCode = error.optInt("code", code);
                } catch (Exception ignored) { }
                throw new RpcError(rpcCode);
            }
            return conn;
        } catch (Exception failure) { close(conn); throw failure; }
    }

    private JSONObject readEvent(InputStream input, long id) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        JSONObject event = new JSONObject(data.toString()); data.setLength(0);
                        if (event.optLong("id", -1) == id) return event;
                    }
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                    if (data.length() > 4 * 1024 * 1024) throw new IOException("MCP 响应过大");
                }
            }
        }
        return null;
    }

    private String readText(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                if (output.size() > 4 * 1024 * 1024) throw new IOException("MCP 响应过大");
            }
            return output.toString("UTF-8");
        }
    }

    private void close(HttpURLConnection conn) { if (conn != null) conn.disconnect(); if (active == conn) active = null; }
    void preventRequests() { cancelled = true; }
    void cancel() { cancelled = true; HttpURLConnection conn = active; if (conn != null) conn.disconnect(); ready = false; }

    static final class RpcError extends IOException {
        final int code;
        RpcError(int value) { super("MCP 错误码 " + value); code = value; }
    }
}
