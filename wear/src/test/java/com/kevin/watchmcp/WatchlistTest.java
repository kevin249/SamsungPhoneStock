package com.kevin.watchmcp;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class WatchlistTest {
    @Test public void movingUpSwapsWithPreviousAndKeepsSelectionAfterRestore() {
        Watchlist list = new Watchlist(null, "sz300750");
        list.moveUp("sz300750");
        assertEquals(Arrays.asList("sh600519", "sz300750", "sz000001", "sh601318"), list.symbols());
        Watchlist restored = new Watchlist(list.serialize(), list.selected());
        assertEquals(list.symbols(), restored.symbols());
        assertEquals("sz300750", restored.selected());
    }

    @Test public void movingFirstUpWrapsToLastWithoutChangingSelection() {
        Watchlist list = new Watchlist(null, "sh600519");
        list.moveUp("sh600519");
        assertEquals(Arrays.asList("sz000001", "sz300750", "sh601318", "sh600519"), list.symbols());
        assertEquals("sh600519", list.selected());
        list.moveUp("sh600519");
        assertEquals(Arrays.asList("sz000001", "sz300750", "sh600519", "sh601318"), list.symbols());
    }

    @Test public void movingOnlyStockIsSafeAndUnknownStockCannotChangeOrder() {
        Watchlist list = new Watchlist("sh600519", "sh600519");
        list.moveUp("sh600519");
        assertEquals("sh600519", list.serialize());
        assertEquals("sh600519", list.selected());
        assertThrows(IllegalArgumentException.class, () -> list.moveUp("sz000001"));
        assertEquals("sh600519", list.serialize());
        Watchlist empty = new Watchlist("", "");
        assertThrows(IllegalArgumentException.class, () -> empty.moveUp("sh600519"));
        assertTrue(empty.isEmpty());
    }
    @Test public void movingCyclesBothWaysAndRestoresSelection() {
        Watchlist list = new Watchlist(null, "sh600519");
        assertTrue(list.move(1));
        assertEquals("sz000001", list.selected());
        assertTrue(list.move(-1));
        assertEquals("sh600519", list.selected());
        assertTrue(list.move(-1));
        assertEquals("sh601318", list.selected());
        assertEquals("sh601318", new Watchlist(list.serialize(), list.selected()).selected());
        assertTrue(list.move(1));
        assertEquals("sh600519", list.selected());
        assertFalse(list.move(0));
    }

    @Test public void movingEmptyOrSingleStockDoesNotChangeSelection() {
        for (String saved : new String[]{"", "sh600519"}) {
            Watchlist list = new Watchlist(saved, "sh600519");
            String original = list.selected();
            assertFalse(list.move(1));
            assertFalse(list.move(-1));
            assertEquals(original, list.selected());
        }
    }

    @Test public void firstUpgradeKeepsDefaultsAndExistingSelection() {
        Watchlist list = new Watchlist(null, "sz300750");
        assertEquals(Arrays.asList("sh600519", "sz000001", "sz300750", "sh601318"), list.symbols());
        assertEquals("sz300750", list.selected());
    }

    @Test public void supportsBarePrefixedAndSuffixedCodes() {
        for (String raw : new String[]{"600519", "SH600519", "600519.SH", " sh 600519 "}) {
            assertEquals("sh600519", Watchlist.normalize(raw));
        }
        assertEquals("sz000001", Watchlist.normalize("000001"));
        assertEquals("sz300750", Watchlist.normalize("300750"));
        assertEquals("sz159915", Watchlist.normalize("159915"));
        assertEquals("sh688001", Watchlist.normalize("688001"));
        assertEquals("bj920001", Watchlist.normalize("920001"));
        assertEquals("bj830001", Watchlist.normalize("830001"));
        assertEquals("bj920001", Watchlist.normalize("920001.BJ"));
        // 显式指定交易所时，不使用数字代码的自动市场推断。
        assertEquals("sh000001", Watchlist.normalize("sh000001"));
    }

    @Test public void rejectsMalformedInputWithoutChangingList() {
        Watchlist list = new Watchlist(null, null);
        String saved = list.serialize();
        for (String raw : new String[]{"", "60051", "6005190", "贵州茅台", "600A19", "hk00700", "600519,000001", "１２３４５６"}) {
            assertNull(Watchlist.normalize(raw));
            assertThrows(IllegalArgumentException.class, () -> list.add(raw));
            assertEquals(saved, list.serialize());
        }
        assertNull(Watchlist.normalize(null));
    }

    @Test public void addAndRestorePreserveOrderAndSelection() {
        Watchlist list = new Watchlist(null, "sz300750");
        list.add("688001.SH");
        list.add("920001");
        Watchlist restored = new Watchlist(list.serialize(), list.selected());
        assertEquals(list.symbols(), restored.symbols());
        assertEquals("sz300750", restored.selected());
        assertEquals("bj920001", restored.symbols().get(5));
    }

    @Test public void duplicateAliasesAreRejected() {
        Watchlist list = new Watchlist(null, null);
        assertThrows(IllegalArgumentException.class, () -> list.add("600519.SH"));
        assertThrows(IllegalArgumentException.class, () -> list.replace("sz000001", "SH600519"));
        assertEquals(4, list.symbols().size());
        assertEquals("sz000001", list.symbols().get(1));
    }

    @Test public void replacingCurrentStockKeepsPositionAndTracksSelection() {
        Watchlist list = new Watchlist(null, "sz000001");
        list.replace("sz000001", "600036");
        assertEquals("sh600036", list.selected());
        assertEquals("sh600036", list.symbols().get(1));
        Watchlist restored = new Watchlist(list.serialize(), list.selected());
        assertEquals("sh600036", restored.selected());
        assertFalse(restored.symbols().contains("sz000001"));
    }

    @Test public void replacingAnotherStockDoesNotChangeSelection() {
        Watchlist list = new Watchlist(null, "sz300750");
        list.replace("sz000001", "600036");
        assertEquals("sz300750", list.selected());
        list.replace("sz300750", "300750.SZ");
        assertEquals("sz300750", list.selected());
    }

    @Test public void deletingCurrentStockSelectsNextThenPrevious() {
        Watchlist list = new Watchlist(null, "sz000001");
        list.remove("sz000001");
        assertEquals("sz300750", list.selected());
        list.select("sh601318");
        list.remove("sh601318");
        assertEquals("sz300750", list.selected());
    }

    @Test public void deletingAnotherStockPreservesSelectionAfterRestore() {
        Watchlist list = new Watchlist(null, "sz300750");
        list.remove("sh600519");
        Watchlist restored = new Watchlist(list.serialize(), list.selected());
        assertEquals("sz300750", restored.selected());
        assertFalse(restored.symbols().contains("sh600519"));
    }

    @Test public void deletingAllStaysEmptyAfterRestartAndFirstAddSelectsIt() {
        Watchlist list = new Watchlist(null, null);
        for (String code : list.symbols().toArray(new String[0])) list.remove(code);
        assertTrue(list.isEmpty());
        assertEquals("", list.selected());
        Watchlist restored = new Watchlist(list.serialize(), "sh600519");
        assertTrue(restored.isEmpty());
        assertEquals("", restored.selected());
        restored.add("000001");
        assertEquals("sz000001", restored.selected());
        assertEquals(1, restored.symbols().size());
    }

    @Test public void restorationDropsInvalidAndDuplicateRowsWithoutResettingDefaults() {
        Watchlist list = new Watchlist("bad\n600519\nSH600519\n\n000001", "invalid");
        assertEquals(Arrays.asList("sh600519", "sz000001"), list.symbols());
        assertEquals("sh600519", list.selected());
        assertTrue(new Watchlist("bad", null).isEmpty());
    }

    @Test public void selectingPersistsAndUnknownStockCannotMutateList() {
        Watchlist list = new Watchlist(null, null);
        list.select("sh601318");
        assertEquals("sh601318", new Watchlist(list.serialize(), list.selected()).selected());
        String saved = list.serialize();
        assertThrows(IllegalArgumentException.class, () -> list.select("sh600036"));
        assertThrows(IllegalArgumentException.class, () -> list.remove("sh600036"));
        assertThrows(IllegalArgumentException.class, () -> list.replace("sh600036", "600000"));
        assertThrows(UnsupportedOperationException.class, () -> list.symbols().clear());
        assertEquals(saved, list.serialize());
    }
}
