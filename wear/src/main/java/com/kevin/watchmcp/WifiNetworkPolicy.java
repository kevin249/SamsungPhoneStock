package com.kevin.watchmcp;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.util.Log;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 前台接收服务持有 Wi-Fi 请求；只使用已验证网络，断开时选择手机蓝牙。 */
final class WifiNetworkPolicy implements AutoCloseable {
    private static volatile WifiNetworkPolicy active;
    private final ConnectivityManager connectivity;
    private final Handler handler;
    private final Runnable changed;
    private final Map<Network, NetworkCapabilities> networks = new HashMap<>();
    private final Set<Network> blocked = new HashSet<>();
    private Network previousBinding;
    private volatile Network selected;
    private volatile String transport = "等待网络";
    private boolean observing, wifiRequested, closed;

    private final ConnectivityManager.NetworkCallback observer = new ConnectivityManager.NetworkCallback() {
        @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            if (closed) return;
            networks.put(network, new NetworkCapabilities(caps));
            choose();
        }
        @Override public void onLost(Network network) {
            if (closed) return;
            networks.remove(network);
            blocked.remove(network);
            choose();
        }
        @Override public void onBlockedStatusChanged(Network network, boolean isBlocked) {
            if (closed) return;
            if (isBlocked) blocked.add(network); else blocked.remove(network);
            choose();
        }
    };
    // 这个请求持续存在，防止系统仅因蓝牙可用或熄屏而主动释放 Wi-Fi。
    private final ConnectivityManager.NetworkCallback keepWifi = new ConnectivityManager.NetworkCallback() { };

    WifiNetworkPolicy(Context context, Handler handler, Runnable changed) {
        connectivity = context.getSystemService(ConnectivityManager.class);
        this.handler = handler;
        this.changed = changed;
    }

    void start() {
        previousBinding = connectivity.getBoundNetworkForProcess();
        active = this;
        try {
            connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), observer, handler);
            observing = true;
            connectivity.requestNetwork(new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), keepWifi, handler);
            wifiRequested = true;
        } catch (RuntimeException error) {
            close();
            throw error;
        }
    }

    private int rank(Network network) {
        NetworkCapabilities caps = networks.get(network);
        if (caps == null || blocked.contains(network)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED)) return 0;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return 3;
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) return 2;
        return 1;
    }

    private void choose() {
        Network next = selected;
        int best = rank(next);
        if (best == 0) next = null;
        for (Network candidate : networks.keySet()) {
            int score = rank(candidate);
            if (score > best) { next = candidate; best = score; }
        }
        if (java.util.Objects.equals(next, selected)) return;
        // 绑定覆盖应用默认网络；HTTP 还会显式使用 Network.openConnection，避免复用旧网络连接。
        connectivity.bindProcessToNetwork(next);
        selected = next;
        transport = best == 3 ? "Wi-Fi" : best == 2 ? "手机蓝牙" : best == 1 ? "其他网络" : "等待网络";
        Log.i("WatchMcpNetwork", "route=" + transport + " network=" + next + " wifiRequested=" + wifiRequested);
        changed.run();
    }

    boolean hasNetwork() { return selected != null; }
    String transport() { return transport; }
    Network selectedNetwork() { return selected; }
    boolean isWifiRequested() { return wifiRequested; }
    static WifiNetworkPolicy current() { return active; }

    static HttpURLConnection open(URL url) throws IOException {
        WifiNetworkPolicy policy = active;
        if (policy == null) return (HttpURLConnection) url.openConnection();
        Network network = policy.selected;
        if (network == null) throw new IOException("No validated network available");
        return (HttpURLConnection) network.openConnection(url);
    }

    @Override public void close() {
        closed = true;
        if (observing) { connectivity.unregisterNetworkCallback(observer); observing = false; }
        if (wifiRequested) { connectivity.unregisterNetworkCallback(keepWifi); wifiRequested = false; }
        if (java.util.Objects.equals(connectivity.getBoundNetworkForProcess(), selected)) {
            connectivity.bindProcessToNetwork(previousBinding);
        }
        selected = null;
        if (active == this) active = null;
    }
}
