package com.kevin.phonekline;

import android.content.Context;
import android.content.res.ColorStateList;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.kevin.market.StockSearch;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

/** Search by spelling or select an explicit market code. Typing alone never changes the active stock. */
final class PhoneStockPicker extends LinearLayout {
    private static final int INK = 0xffebede7, MUTED = 0xffb4bcb6;
    private final EditText input;
    private final TextView message;
    private final LinearLayout results;
    private final Consumer<String> onSelected;
    private final StockSearch client = new StockSearch();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable search = this::submitQuery;
    private ExecutorService worker;
    private Future<?> pending;
    private int generation;
    private boolean setting;

    PhoneStockPicker(Context context, String current, Consumer<String> onSelected) {
        super(context);
        this.onSelected = onSelected;
        setOrientation(VERTICAL);
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new LayoutParams(-1, -2));
        input = new EditText(context);
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        input.setTextColor(INK);
        input.setTextSize(16);
        input.setHint("代码、名称、全拼或首字母");
        input.setContentDescription("搜索股票，支持代码、名称、拼音和首字母");
        input.setSelectAllOnFocus(true);
        input.setText(current);
        row.addView(input, new LayoutParams(0, dp(50), 1));
        Button submit = new Button(context);
        submit.setText("查询 / 切换");
        submit.setTextColor(INK);
        submit.setAllCaps(false);
        submit.setMinHeight(dp(48));
        submit.setBackgroundTintList(ColorStateList.valueOf(0xff27352f));
        submit.setOnClickListener(v -> confirm());
        row.addView(submit, new LayoutParams(-2, -2));

        message = new TextView(context);
        message.setTextSize(13);
        message.setTextColor(MUTED);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        message.setVisibility(GONE);
        addView(message, new LayoutParams(-1, -2));
        results = new LinearLayout(context);
        results.setOrientation(VERTICAL);
        addView(results, new LayoutParams(-1, -2));

        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!setting) scheduleQuery(350);
            }
            @Override public void afterTextChanged(Editable value) { }
        });
        input.setOnEditorActionListener((v, action, event) -> {
            if (action != EditorInfo.IME_ACTION_SEARCH) return false;
            confirm();
            return true;
        });
    }

    private int dp(float size) { return Math.round(size * getResources().getDisplayMetrics().density); }

    private void confirm() {
        String raw = input.getText().toString().trim();
        String code = StockSearch.normalize(raw);
        if (code != null) {
            select(code);
        } else if (raw.isEmpty()) {
            input.setError("请输入股票代码、名称或拼音");
        } else {
            hideKeyboard();
            scheduleQuery(0);
        }
    }

    private void cancelQuery() {
        generation++;
        main.removeCallbacks(search);
        if (pending != null) pending.cancel(true);
        pending = null;
    }

    private void scheduleQuery(long delay) {
        cancelQuery();
        results.removeAllViews();
        if (input.getText().toString().trim().isEmpty()) {
            message.setVisibility(GONE);
            return;
        }
        message.setText("正在查询…");
        message.setVisibility(VISIBLE);
        main.postDelayed(search, delay);
    }

    private void submitQuery() {
        String query = input.getText().toString().trim();
        int version = generation;
        if (worker == null) worker = Executors.newSingleThreadExecutor();
        pending = worker.submit(() -> {
            try {
                List<StockSearch.Stock> matches = client.search(query);
                main.post(() -> present(version, matches, matches.isEmpty()
                        ? "未找到匹配的 A 股，请更换关键词" : "点选股票切换行情"));
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            } catch (Exception unavailable) {
                main.post(() -> present(version, Collections.emptyList(), "搜索暂不可用，可重试或直接输入六位代码"));
            }
        });
    }

    private void present(int version, List<StockSearch.Stock> matches, String prompt) {
        if (version != generation || !isAttachedToWindow()) return;
        results.removeAllViews();
        message.setText(prompt);
        for (StockSearch.Stock stock : matches) {
            Button item = new Button(getContext());
            item.setText(stock.name + "    " + stock.symbol);
            item.setContentDescription("切换至 " + stock.name + " " + stock.symbol);
            item.setTextSize(16);
            item.setAllCaps(false);
            item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            item.setMinHeight(dp(48));
            item.setTextColor(INK);
            item.setBackgroundTintList(ColorStateList.valueOf(0xff18241e));
            item.setOnClickListener(v -> {
                if (version != generation) return;
                getContext().getSharedPreferences("phone_dial",Context.MODE_PRIVATE).edit()
                        .putString("stock_name_"+stock.symbol,stock.name).apply();
                select(stock.symbol);
            });
            results.addView(item, new LayoutParams(-1, -2));
        }
    }

    private void select(String code) {
        cancelQuery();
        setting = true;
        input.setText(code);
        input.setSelection(code.length());
        setting = false;
        message.setVisibility(GONE);
        results.removeAllViews();
        hideKeyboard();
        onSelected.accept(code);
    }

    private void hideKeyboard() {
        InputMethodManager keyboard = getContext().getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(input.getWindowToken(), 0);
    }

    @Override protected void onDetachedFromWindow() {
        cancelQuery();
        if (worker != null) worker.shutdownNow();
        worker = null;
        super.onDetachedFromWindow();
    }
}
