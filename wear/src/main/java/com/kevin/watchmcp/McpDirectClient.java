package com.kevin.watchmcp;

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
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** 手表直连 Streamable HTTP MCP；支持 2026 listen 和旧版资源订阅。 */
final class McpDirectClient {
    // 更新信号触发即时读取；定时补读防止订阅信号遗漏。
    private static final long RESOURCE_REFRESH_INTERVAL_MS = 10000;
    interface Listener {
        void status(String value);
        void resource(JSONObject payload);
    }
    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }
    private final ConnectionFactory connections;
    private final String address, apiKey;
    private final Listener listener;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final Set<HttpURLConnection> sockets = Collections.synchronizedSet(new HashSet<>());
    private final AtomicLong ids = new AtomicLong();
    private final Object wake = new Object();
    private volatile boolean running = true, dirty;
    private String version = "2026-07-28", sessionId = "";
    private volatile String phase = "连接";
    private volatile long retryDelay = 3000;

    McpDirectClient(String address, String apiKey, Listener listener) {
        this(address, apiKey, listener, url -> (HttpURLConnection) url.openConnection());
    }

    McpDirectClient(String address, String apiKey, Listener listener, ConnectionFactory connections) {
        this.address = address; this.apiKey = apiKey; this.listener = listener;
        this.connections = connections;
    }

    void start() {
        if (address.isEmpty() || apiKey.isEmpty()) { listener.status("请设置手表 MCP 接口"); return; }
        workers.execute(() -> {
            while (running) {
                try { connect(); }
                catch (Exception error) {
                    String reason = error instanceof RpcError ? "错误码 " + ((RpcError) error).code : error.getClass().getSimpleName();
                    if (running) {
                        android.util.Log.w("WatchMcp", phase + ": " + reason);
                        listener.status(error instanceof RpcError && ((RpcError) error).code == 401 ? "MCP Key 无效或已过期" : "MCP " + phase + "失败 · " + reason);
                    }
                } finally { closeSockets(); }
                if (running) try {
                    Thread.sleep(retryDelay);
                    retryDelay = Math.min(60000, retryDelay * 2);
                } catch (InterruptedException ignored) { break; }
            }
        });
    }

    boolean negotiate() throws Exception {
        version = "2026-07-28";
        sessionId = "";
        boolean modern = true;
        phase = "协议协商";
        try { rpc("server/discover", new JSONObject()); }
        catch (RpcError error) {
            if (error.code != -32601 && error.code != -32602) throw error;
            modern = false;
            version = "2025-11-25";
            JSONObject init = rpc("initialize", new JSONObject().put("protocolVersion", version)
                    .put("capabilities", new JSONObject()).put("clientInfo", clientInfo()));
            version = init.optString("protocolVersion", version);
            notifyInitialized();
        }
        return modern;
    }

    JSONObject tool(String name, JSONObject args) throws Exception {
        return toolPayload(rpc("tools/call", new JSONObject().put("name", name).put("arguments", args)));
    }

    private void connect() throws Exception {
        listener.status("手表正在直连 MCP…");
        boolean modern = negotiate();
        phase = "发现通知资源";
        JSONObject info = toolPayload(rpc("tools/call", new JSONObject().put("name", "get_notification_stream").put("arguments", new JSONObject())));
        String uri = info.optString("resource_uri");
        if (uri.isEmpty()) throw new IOException("Missing notification resource");
        final boolean modernProtocol = modern;
        CountDownLatch acknowledged = new CountDownLatch(1);
        if (!modern) rpc("resources/subscribe", new JSONObject().put("uri", uri));
        Future<?> subscription = workers.submit(() -> {
            try { stream(uri, modernProtocol, acknowledged); }
            catch (Exception error) { throw new RuntimeException("Subscription ended"); }
        });
        try {
            phase = "订阅通知";
            if (!acknowledged.await(15, TimeUnit.SECONDS)) throw new IOException("No subscription acknowledgement");
            while (running) {
                if (subscription.isDone()) { subscription.get(); throw new IOException("Subscription closed"); }
                dirty = false;
                phase = "读取通知";
                JSONObject resource = rpc("resources/read", new JSONObject().put("uri", uri));
                JSONArray contents = resource.optJSONArray("contents");
                boolean found = false;
                if (contents != null) for (int i = 0; i < contents.length(); i++) {
                    JSONObject content = contents.optJSONObject(i);
                    if (content != null && content.has("text")) {
                        listener.resource(new JSONObject(content.getString("text")));
                        found = true;
                        break;
                    }
                }
                if (!found) throw new IOException("No notification payload");
                retryDelay = 3000;
                listener.status("MCP 已连接 · 手表直连");
                synchronized (wake) { if (!dirty && running && !subscription.isDone()) wake.wait(RESOURCE_REFRESH_INTERVAL_MS); }
            }
        } finally { closeSockets(); subscription.cancel(true); }
    }

    private JSONObject clientInfo() throws Exception {
        return new JSONObject().put("name", "SamsungWatchMCP").put("version", "0.6.6");
    }

    private JSONObject request(String method, JSONObject params, long id) throws Exception {
        if (version.equals("2026-07-28")) params.put("_meta", new JSONObject()
                .put("io.modelcontextprotocol/protocolVersion", version)
                .put("io.modelcontextprotocol/clientInfo", clientInfo())
                .put("io.modelcontextprotocol/clientCapabilities", new JSONObject()));
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("method", method).put("params", params);
    }

    private HttpURLConnection open(String method, JSONObject payload, int timeout) throws Exception {
        if (!running) throw new java.io.InterruptedIOException("Client stopped");
        HttpURLConnection connection = connections.open(new URL(address));
        sockets.add(connection);
        if (!running) { close(connection); throw new java.io.InterruptedIOException("Client stopped"); }
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(false);
        connection.setRequestProperty("Authorization", "Bearer " + apiKey);
        connection.setRequestProperty("Accept", "application/json, text/event-stream");
        connection.setRequestProperty("Mcp-Protocol-Version", version);
        if (!sessionId.isEmpty() && !version.equals("2026-07-28")) connection.setRequestProperty("Mcp-Session-Id", sessionId);
        if (payload != null) {
            connection.setRequestProperty("Mcp-Method", payload.optString("method"));
            JSONObject params = payload.optJSONObject("params");
            String rpcMethod = payload.optString("method");
            if (params != null && ("tools/call".equals(rpcMethod) || "prompts/get".equals(rpcMethod) || "resources/read".equals(rpcMethod))) {
                String name = params.optString("resources/read".equals(rpcMethod) ? "uri" : "name");
                if (!name.matches("[\\x21-\\x7E]+") || (name.startsWith("=?base64?") && name.endsWith("?="))) name = "=?base64?" + android.util.Base64.encodeToString(name.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP) + "?=";
                connection.setRequestProperty("Mcp-Name", name);
            }
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setDoOutput(true);
            byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            try (java.io.OutputStream output = connection.getOutputStream()) { output.write(body); }
        }
        int code = connection.getResponseCode();
        String assignedSession = connection.getHeaderField("Mcp-Session-Id");
        if (assignedSession != null) sessionId = assignedSession;
        if (code == 401 || code == 403) throw new RpcError(401);
        if (code < 200 || code >= 300) {
            int rpcCode = code;
            try {
                JSONObject error = new JSONObject(readText(connection.getErrorStream())).optJSONObject("error");
                if (error != null) rpcCode = error.optInt("code", code);
            } catch (Exception ignored) { }
            throw new RpcError(rpcCode);
        }
        return connection;
    }

    private JSONObject rpc(String method, JSONObject params) throws Exception {
        long id = ids.incrementAndGet();
        HttpURLConnection connection = null;
        try {
            connection = open("POST", request(method, params, id), 20000);
            String type = connection.getContentType();
            JSONObject message;
            if (type != null && type.contains("text/event-stream")) {
                final JSONObject[] result = {null};
                readEvents(connection.getInputStream(), value -> {
                    if (value.optLong("id", -1) == id) { result[0] = value; return false; }
                    return true;
                });
                message = result[0];
            } else {
                message = new JSONObject(readText(connection.getInputStream()));
            }
            if (message == null) throw new IOException("No RPC result");
            JSONObject error = message.optJSONObject("error");
            if (error != null) throw new RpcError(error.optInt("code"));
            JSONObject result = message.optJSONObject("result");
            if (result == null) throw new IOException("Invalid RPC result");
            return result;
        } finally { close(connection); }
    }

    private void notifyInitialized() throws Exception {
        HttpURLConnection connection = null;
        try { connection = open("POST", new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), 20000); }
        finally { close(connection); }
    }

    private void stream(String uri, boolean modern, CountDownLatch acknowledged) throws Exception {
        HttpURLConnection connection = null;
        try {
            JSONObject payload = modern ? request("subscriptions/listen", new JSONObject().put("notifications", new JSONObject().put("resourceSubscriptions", new JSONArray().put(uri))), ids.incrementAndGet()) : null;
            connection = open(modern ? "POST" : "GET", payload, 90000);
            if (!modern) acknowledged.countDown();
            readEvents(connection.getInputStream(), message -> {
                String method = message.optString("method");
                JSONObject params = message.optJSONObject("params");
                if ("notifications/subscriptions/acknowledged".equals(method)) {
                    JSONArray subscriptions = params == null || params.optJSONObject("notifications") == null ? null : params.getJSONObject("notifications").optJSONArray("resourceSubscriptions");
                    boolean accepted = false;
                    if (subscriptions != null) for (int i = 0; i < subscriptions.length(); i++) accepted |= uri.equals(subscriptions.optString(i));
                    if (!accepted) throw new IOException("Subscription not honored");
                    acknowledged.countDown();
                } else if ("notifications/resources/updated".equals(method) && params != null && uri.equals(params.optString("uri"))) {
                    synchronized (wake) { dirty = true; wake.notifyAll(); }
                } else if (message.has("error") || message.has("result")) return false;
                return running;
            });
        } finally {
            close(connection);
            synchronized (wake) { dirty = true; wake.notifyAll(); }
        }
    }

    private interface EventReader { boolean event(JSONObject value) throws Exception; }

    private void readEvents(InputStream input, EventReader reader) throws Exception {
        try (BufferedReader stream = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while (running && (line = stream.readLine()) != null) {
                if (line.isEmpty()) {
                    if (data.length() > 0) {
                        JSONObject value = new JSONObject(data.toString());
                        data.setLength(0);
                        if (!reader.event(value)) return;
                    }
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                    if (data.length() > 4 * 1024 * 1024) throw new IOException("Event too large");
                }
            }
        }
    }

    static String readText(InputStream stream) throws Exception {
        try (InputStream input = stream; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                output.write(buffer, 0, count);
                if (output.size() > 4 * 1024 * 1024) throw new IOException("Response too large");
            }
            return output.toString("UTF-8");
        }
    }

    private JSONObject toolPayload(JSONObject result) throws Exception {
        if (result.optBoolean("isError")) throw new IOException("MCP tool error");
        JSONObject structured = result.optJSONObject("structuredContent");
        if (structured != null) return structured;
        JSONArray content = result.optJSONArray("content");
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject block = content.optJSONObject(i);
            if (block != null && "text".equals(block.optString("type"))) return new JSONObject(block.getString("text"));
        }
        throw new IOException("Missing tool payload");
    }

    private void close(HttpURLConnection connection) {
        if (connection != null) { sockets.remove(connection); connection.disconnect(); }
    }

    private void closeSockets() {
        HttpURLConnection[] copy;
        synchronized (sockets) { copy = sockets.toArray(new HttpURLConnection[0]); sockets.clear(); }
        for (HttpURLConnection connection : copy) connection.disconnect();
    }

    void stop() {
        running = false;
        synchronized (wake) { wake.notifyAll(); }
        workers.shutdownNow();
        // Android 的 disconnect 可能等待正在读取的 SSE 流；不能阻塞服务主线程和新网络重连。
        Thread cleanup = new Thread(this::closeSockets, "mcp-close");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    private static final class RpcError extends IOException {
        final int code;
        RpcError(int code) { super("MCP error " + code); this.code = code; }
    }
}
