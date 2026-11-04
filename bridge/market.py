"""行情缓存与异步刷新；失败保留最后一帧并明确标注。"""
from __future__ import annotations

import datetime as dt
import threading
import time
from concurrent.futures import ThreadPoolExecutor

from .vendor.kline import KlineClient

SHANGHAI = dt.timezone(dt.timedelta(hours=8))
PERIODS = {"1d": "日 K", "5m": "5 分钟", "1m": "1 分钟", "1w": "周 K"}


def now() -> str:
    return dt.datetime.now(SHANGHAI).isoformat(timespec="seconds")


class MarketCache:
    def __init__(self, demo: bool = False):
        self.demo = demo
        self.lock = threading.Lock()
        self.entries: dict[tuple[str, str], dict] = {}
        self.pool = ThreadPoolExecutor(max_workers=2, thread_name_prefix="kline")
        self.clients = threading.local()

    def snapshot(self, symbol: str, period: str) -> dict:
        key = (symbol, period)
        with self.lock:
            entry = self.entries.setdefault(key, {"data": None, "error": "", "updated_at": "", "requested": 0.0, "loading": False})
            ttl = 20 if period.endswith("m") else 60
            if not entry["loading"] and time.monotonic() - entry["requested"] >= ttl:
                entry["loading"] = True
                entry["requested"] = time.monotonic()
                self.pool.submit(self._refresh, symbol, period)
            return {k: v for k, v in entry.items() if k != "requested"}

    def _refresh(self, symbol: str, period: str) -> None:
        try:
            if self.demo:
                data = demo_kline(symbol, period)
            else:
                if not hasattr(self.clients, "client"):
                    self.clients.client = KlineClient(source="auto")
                result = self.clients.client.fetch(symbol, period=period, limit=48)
                if result.error or not result.bars:
                    raise ValueError(result.error or "行情为空")
                data = result.to_dict()
            with self.lock:
                self.entries[(symbol, period)].update(data=data, error="", updated_at=now())
        except Exception as exc:
            with self.lock:
                self.entries[(symbol, period)]["error"] = str(exc)[:120] or type(exc).__name__
        finally:
            with self.lock:
                self.entries[(symbol, period)]["loading"] = False

    def close(self) -> None:
        self.pool.shutdown(wait=True, cancel_futures=True)


def demo_kline(symbol: str, period: str) -> dict:
    import math
    bars = []
    price = 52.6
    date = dt.datetime.now(SHANGHAI).replace(hour=15, minute=0, second=0, microsecond=0)
    for i in range(48):
        opening = price
        price += math.sin(i * .73) * .42 + .075
        stamp = date - (dt.timedelta(minutes=(47 - i) * (5 if period == "5m" else 1)) if period.endswith("m") else dt.timedelta(days=(47 - i) * (7 if period == "1w" else 1)))
        bars.append({"time": stamp.isoformat(timespec="minutes"), "open": opening, "close": price, "high": max(price, opening) + .24, "low": min(price, opening) - .18, "volume": 1500 + 900 * abs(math.sin(i))})
    return {"symbol": symbol, "name": "演示行情", "period": period, "period_label": PERIODS[period], "source_label": "模拟数据", "adjust": "none", "bars": bars}
