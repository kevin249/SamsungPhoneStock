package com.kevin.watchmcp;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** 自选列表与当前股票；不依赖联网，空列表也会保留。 */
final class Watchlist {
    private static final String DEFAULTS = "sh600519\nsz000001\nsz300750\nsh601318";
    private final List<String> symbols = new ArrayList<>();
    private String selected;

    Watchlist(String saved, String preferred) {
        // null 表示首次升级；空字符串表示用户已主动清空，不能恢复默认自选。
        for (String value : (saved == null ? DEFAULTS : saved).split("\n")) {
            String code = normalize(value);
            if (code != null && !symbols.contains(code)) symbols.add(code);
        }
        String code = normalize(preferred);
        selected = symbols.contains(code) ? code : symbols.isEmpty() ? "" : symbols.get(0);
    }

    List<String> symbols() { return Collections.unmodifiableList(symbols); }
    String selected() { return selected; }
    boolean isEmpty() { return symbols.isEmpty(); }
    String serialize() { return String.join("\n", symbols); }

    void select(String code) {
        requireMember(code);
        selected = code;
    }

    /** 按自选顺序循环切换；空列表、单只自选或回到原位时不触发刷新。 */
    boolean move(int offset) {
        if (symbols.size() < 2) return false;
        int current = symbols.indexOf(selected);
        int next = Math.floorMod(current + offset, symbols.size());
        if (next == current) return false;
        selected = symbols.get(next);
        return true;
    }

    void add(String raw) {
        String code = requireCode(raw);
        if (symbols.contains(code)) throw new IllegalArgumentException("该股票已在自选中");
        symbols.add(code);
        if (selected.isEmpty()) selected = code;
    }

    void replace(String original, String raw) {
        int index = requireMember(original);
        String code = requireCode(raw);
        if (!code.equals(original) && symbols.contains(code)) throw new IllegalArgumentException("该股票已在自选中");
        symbols.set(index, code);
        if (selected.equals(original)) selected = code;
    }

    void remove(String code) {
        int index = requireMember(code);
        symbols.remove(index);
        if (selected.equals(code)) selected = symbols.isEmpty() ? "" : symbols.get(Math.min(index, symbols.size() - 1));
    }

    /** 第一只上移时循环到末尾；排序不改变当前查看的股票。 */
    void moveUp(String code) {
        int index = requireMember(code);
        if (symbols.size() < 2) return;
        if (index == 0) {
            symbols.remove(0);
            symbols.add(code);
        } else Collections.swap(symbols, index, index - 1);
    }

    private int requireMember(String code) {
        int index = symbols.indexOf(code);
        if (index < 0) throw new IllegalArgumentException("该股票不在自选中");
        return index;
    }

    private static String requireCode(String raw) {
        String code = normalize(raw);
        if (code == null) throw new IllegalArgumentException("请输入 6 位代码，如 600519");
        return code;
    }

    /** 与桥接端代码规则一致；显式市场前缀/后缀优先，仅检查格式，不保证证券存在。 */
    static String normalize(String raw) {
        if (raw == null) return null;
        String value = raw.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        if (value.matches("(sh|sz|bj)[0-9]{6}")) return value;
        if (value.matches("[0-9]{6}\\.(sh|sz|bj)")) return value.substring(7) + value.substring(0, 6);
        if (!value.matches("[0-9]{6}")) return null;
        String market = value.matches("(00|30|20|15|16|18|39).*") ? "sz"
                : value.matches("(43|83|87|88|92).*") ? "bj" : "sh";
        return market + value;
    }
}
