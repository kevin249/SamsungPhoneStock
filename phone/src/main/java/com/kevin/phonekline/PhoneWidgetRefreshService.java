package com.kevin.phonekline;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** The widget host keeps the last timestamped frame while this short network job is idle. */
public final class PhoneWidgetRefreshService extends JobService {
    private static final int PERIODIC = 0x4b2101, IMMEDIATE = 0x4b2102;
    private static final long HALF_HOUR = 30 * 60_000L;
    private static final long PUBLIC_WINDOW = 17_000L, PRIVATE_WINDOW = 30_000L;
    private static final long PUBLISH_SPACING = 1_500L;

    static void schedule(Context context, boolean immediate) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;
        if (!enabled(context)) {
            cancel(context);
            if (PhoneMarketWidgetProvider.hasWidgets(context)
                    && !context.getSharedPreferences("phone_dial", MODE_PRIVATE).getBoolean("paused", false)
                    && PhoneLiveSchedule.displayHours(context) && !PhoneLiveTradingDay.closed(context)) {
                PhoneLiveTradingDay.refresh(context, () -> {
                    if (PhoneLiveSchedule.inWindow(context)) schedule(context, immediate);
                });
            }
            return;
        }
        ComponentName service = new ComponentName(context, PhoneWidgetRefreshService.class);
        if (scheduler.getPendingJob(PERIODIC) == null) {
            scheduler.schedule(new JobInfo.Builder(PERIODIC, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setPeriodic(HALF_HOUR, 5 * 60_000L)
                    .setPersisted(true)
                    .build());
        }
        if (immediate && scheduler.getPendingJob(IMMEDIATE) == null) {
            scheduler.schedule(new JobInfo.Builder(IMMEDIATE, service)
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(0)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .build());
        }
    }

    static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) {
            scheduler.cancel(IMMEDIATE);
            scheduler.cancel(PERIODIC);
        }
    }

    private static boolean enabled(Context context) {
        return !context.getSharedPreferences("phone_dial", MODE_PRIVATE).getBoolean("paused", false)
                && PhoneMarketWidgetProvider.hasWidgets(context) && PhoneLiveSchedule.inWindow(context);
    }

    private static final class Selection {
        final String symbol, second, address, cipher, iv;
        Selection(SharedPreferences prefs) {
            symbol = PhoneRealtimeCandles.normalize(prefs.getString("symbol", "sz000063"));
            second = PhoneDisplayStocks.second(prefs);
            address = prefs.getString("mcp_url", "");
            cipher = prefs.getString("mcp_key_cipher", "");
            iv = prefs.getString("mcp_key_iv", "");
        }
        boolean current(SharedPreferences prefs) {
            return symbol.equals(PhoneRealtimeCandles.normalize(prefs.getString("symbol", "sz000063")))
                    && second.equals(PhoneDisplayStocks.second(prefs))
                    && address.equals(prefs.getString("mcp_url", ""))
                    && cipher.equals(prefs.getString("mcp_key_cipher", ""))
                    && iv.equals(prefs.getString("mcp_key_iv", ""));
        }
    }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Map<Integer,JobParameters> jobs = new LinkedHashMap<>();
    private final Runnable publish = this::publishLatest;
    private final Runnable begin = this::beginWindow;
    private final SharedPreferences.OnSharedPreferenceChangeListener changed = (prefs, key) -> {
        if (jobs.isEmpty()) return;
        if (key == null || key.equals("paused") || key.equals("symbol") || key.equals("second_symbol") || key.equals("mcp_url")
                || key.equals("mcp_key_cipher") || key.equals("mcp_key_iv")) {
            // A saved URL and its encrypted credential may update in separate preference events.
            releaseSession();
            ui.removeCallbacks(begin);
            ui.post(begin);
        }
    };
    private SharedPreferences prefs;
    private PhoneDisplayFeed session, lowerSession;
    private Selection selection;
    private JSONObject pending, lowerPending;
    private String lastStatus, lowerStatus;
    private JSONObject lastSnapshot, lowerSnapshot;
    private boolean privateSource, emittedFresh, lowerFresh;
    private long nextPublish, hardDeadline;
    private final Runnable finish = () -> complete(!emittedFresh && lastSnapshot != null
            && !lastSnapshot.has("price"));

    @Override public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("phone_dial", MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(changed);
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (PhoneLiveMarketService.isStreaming() || !enabled(this)) return false;
        // JobScheduler can dispatch the one-shot concurrently with its periodic sibling.
        // Both await the same subscription and finish with it.
        boolean first = jobs.isEmpty();
        jobs.put(params.getJobId(), params);
        if (first) {
            hardDeadline = SystemClock.uptimeMillis() + PRIVATE_WINDOW;
            ui.post(begin);
        }
        return true;
    }

    private void beginWindow() {
        if (jobs.isEmpty()) return;
        if (!enabled(this) || SystemClock.uptimeMillis() >= hardDeadline) {
            complete(false);
            return;
        }
        selection = new Selection(prefs);
        if (selection.symbol.isEmpty()) {
            complete(false);
            return;
        }
        final Selection run = selection;
        String key = run.address.isEmpty() ? "" : PhoneSecretStore.load(prefs);
        privateSource = !key.isEmpty() && run.address.startsWith("https://");
        nextPublish = 0;
        emittedFresh = lowerFresh = false;
        session = new PhoneDisplayFeed(prefs, (snapshot, status) -> accept(run, false, snapshot, status));
        long duration = privateSource ? PRIVATE_WINDOW : PUBLIC_WINDOW;
        ui.postAtTime(finish, Math.min(hardDeadline, SystemClock.uptimeMillis() + duration));
        session.start(run.symbol, PhoneLiveSchedule.displayPeriod(this), 1, true);
        if (!run.second.isEmpty()) {
            lowerSession = new PhoneDisplayFeed(prefs,(snapshot,status) -> accept(run,true,snapshot,status));
            lowerSession.start(run.second,PhoneLiveSchedule.displayPeriod(this),1,true);
        }
        PhoneLiveTradingDay.refresh(this, () -> { });
    }

    private void accept(Selection run, boolean lower, JSONObject data, String status) {
        if (!enabled(this)) { complete(false); return; }
        if (run != selection || jobs.isEmpty() || !run.current(prefs) || prefs.getBoolean("paused", false)
                || data == null || !(lower ? run.second : run.symbol).equals(data.optString("symbol"))) return;
        JSONObject previous = lower ? lowerSnapshot : lastSnapshot;
        if (data == previous && Objects.equals(lower ? lowerStatus : lastStatus, status)) return;
        boolean initialCache = previous == null && "已缓存".equals(data.optString("source"))
                && !data.has("book") && !data.has("fills");
        if (lower) { lowerSnapshot = data; lowerStatus = status; }
        else { lastSnapshot = data; lastStatus = status; }
        // A new feed first replays its public candle cache. LockStar already has that frame
        // and may still be showing its validated depth and fills. Wait for the network.
        if (initialCache) return;
        try {
            // Retain the validated B/S event array and price levels from this complete feed snapshot.
            // Only the provider decides which public fields may be persisted to disk.
            JSONObject updated = PhoneRealtimeCandles.copy(data).put("widget_status", status);
            if (lower) lowerPending = updated; else pending = updated;
        } catch (org.json.JSONException invalid) { return; }
        ui.removeCallbacks(publish);
        ui.postAtTime(publish, Math.max(nextPublish, SystemClock.uptimeMillis()));
        String source = data.optString("source");
        boolean fresh = (!source.isEmpty() && !"已缓存".equals(source)
                || "1d".equals(data.optString("period")) && !status.contains("加载") && !status.contains("重试")) && data.has("price");
        if (lower) lowerFresh |= fresh; else emittedFresh |= fresh;
        if (emittedFresh && (run.second.isEmpty() || lowerFresh)
                && detailsReady(lastSnapshot,lastStatus) && (run.second.isEmpty() || detailsReady(lowerSnapshot,lowerStatus))) {
            // Do not abandon slow detail lanes after receiving the first quote.
            // The final flush delivers the latest accepted snapshot even when coalescing.
            ui.removeCallbacks(finish);
            ui.post(finish);
        }
    }

    private boolean detailsReady(JSONObject data, String status) {
        return data != null && (!privateSource || data.has("book") && data.has("fills") && data.has("high")
                && data.has("low") && data.has("previous"))
                && (!"1d".equals(data.optString("period")) || status != null && !status.contains("加载") && !status.contains("重试"));
    }

    private void publishLatest() {
        if ((pending == null && lowerPending == null) || selection == null || !selection.current(prefs) || !enabled(this)) return;
        JSONObject accepted = pending;
        JSONObject lower = lowerPending;
        pending = lowerPending = null;
        PhoneMarketWidgetProvider.publish(this, accepted, lower);
        nextPublish = SystemClock.uptimeMillis() + PUBLISH_SPACING;
    }

    private void complete(boolean retry) {
        // Capture before calling listeners, as releasing the subscription can coincide with cancellation.
        List<JobParameters> completed = new ArrayList<>(jobs.values());
        try { publishLatest(); }
        finally {
            jobs.clear();
            releaseSession();
            ui.removeCallbacks(begin);
            for (JobParameters params : completed) jobFinished(params, retry && enabled(this) && params.getJobId() == IMMEDIATE);
        }
    }

    private void releaseSession() {
        selection = null;
        ui.removeCallbacks(publish);
        ui.removeCallbacks(finish);
        pending = lowerPending = null;
        lastSnapshot = lowerSnapshot = null;
        lastStatus = lowerStatus = null;
        if (session != null) { session.stop(); session = null; }
        if (lowerSession != null) { lowerSession.stop(); lowerSession = null; }
    }

    @Override public boolean onStopJob(JobParameters params) {
        jobs.remove(params.getJobId());
        if (jobs.isEmpty()) {
            ui.removeCallbacks(begin);
            releaseSession();
        }
        // Network loss can interrupt a user-requested refresh. Let JobScheduler retry
        // with its own constraint and backoff. Periodic jobs already have another run.
        return params.getJobId() == IMMEDIATE && enabled(this);
    }

    @Override public void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(changed);
        jobs.clear();
        ui.removeCallbacks(begin);
        releaseSession();
        super.onDestroy();
    }
}
