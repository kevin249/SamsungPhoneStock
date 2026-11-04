"""关键边界：鉴权、去重持久化、漏消息、断线后画面、真实 HTTP 协议。"""
from __future__ import annotations

import asyncio
import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from bridge.market import MarketCache, demo_kline
from bridge.mcp_worker import McpWorker, unpack
from bridge.server import BridgeServer
from bridge.state import NotificationStore
from bridge.vendor.kline import Kline, Bar, parse_eastmoney, parse_sina, parse_tencent_day


def event(event_id: str, sequence: int) -> dict:
    return {"event_id": event_id, "sequence": sequence, "title": "中文提醒", "body": "股价异动"}


class StoreTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / "events.db"
        self.store = NotificationStore(self.path)

    def tearDown(self):
        self.store.close()
        self.temp.cleanup()

    def test_duplicate_push_and_reconnect_preserve_cursor_and_text(self):
        payload = {"notifications": [event("same", 1)], "latest_sequence": 1}
        self.store.ingest(payload)
        self.store.ingest(payload)
        before = self.store.snapshot()
        self.store.close()
        self.store = NotificationStore(self.path)
        self.store.ingest(payload)
        after = self.store.snapshot()
        self.assertEqual(before["cursor"], after["cursor"])
        self.assertEqual(before["stream_id"], after["stream_id"])
        self.assertEqual(len(after["notifications"]), 1)
        self.assertEqual(after["notifications"][0]["title"], "中文提醒")

    def test_missing_sequence_and_server_sequence_restart(self):
        self.store.ingest({"notifications": [event("first", 10)], "latest_sequence": 10})
        missing = self.store.ingest({"notifications": [event("next", 13)], "latest_sequence": 13})
        self.assertEqual(missing, 2)
        self.assertEqual(self.store.ingest({"notifications": [event("reset", 1)], "latest_sequence": 1}), 0)

    def test_history_order_matches_sequence_even_if_server_rows_reversed(self):
        self.store.ingest({"notifications": [event("b", 2), event("a", 1)]})
        self.assertEqual([r["event_id"] for r in self.store.snapshot()["notifications"]], ["b", "a"])

    def test_bad_rows_do_not_create_phantom_notifications(self):
        self.store.ingest({"notifications": [None, {}, event("", 0), event("valid", 1)]})
        self.assertEqual(len(self.store.snapshot()["notifications"]), 1)


class MarketTests(unittest.TestCase):
    def test_failed_refresh_preserves_last_successful_bars_and_timestamp(self):
        market = MarketCache(demo=True)
        try:
            market.snapshot("sh600519", "1d")
            for _ in range(100):
                if not market.snapshot("sh600519", "1d")["loading"]:
                    break
                time.sleep(.01)
            before = market.snapshot("sh600519", "1d")
            with patch("bridge.market.demo_kline", side_effect=ConnectionError("离线")):
                market._refresh("sh600519", "1d")
            after = market.snapshot("sh600519", "1d")
            self.assertEqual(before["data"], after["data"])
            self.assertEqual(before["updated_at"], after["updated_at"])
            self.assertEqual(after["error"], "离线")
        finally:
            market.close()

    def test_real_market_does_not_substitute_demo_on_failure(self):
        market = MarketCache(demo=False)
        try:
            market.entries[("sh600519", "1d")] = {"data": None, "error": "", "updated_at": "", "requested": 1, "loading": True}
            with patch("bridge.market.KlineClient") as client:
                client.return_value.fetch.return_value = Kline(error="无数据")
                market._refresh("sh600519", "1d")
            self.assertIsNone(market.entries[("sh600519", "1d")]["data"])
            self.assertEqual(market.entries[("sh600519", "1d")]["error"], "无数据")
        finally:
            market.close()

    def test_vendor_field_order_and_fallback_adjustment(self):
        bars = parse_eastmoney({"data": {"klines": ["2026-11-02,10,11,12,9,100"]}}, limit=48)
        self.assertEqual((bars[0].open, bars[0].close, bars[0].high, bars[0].low), (10, 11, 12, 9))
        tencent = parse_tencent_day({"data": {"sh600519": {"qfqday": [["2026-11-02", "10", "11", "12", "9", "100"]]}}}, "sh600519", "day", limit=48)
        self.assertEqual(tencent[0].close, 11)
        sina = parse_sina('[{"day":"2026-11-02","open":"10","close":"11","high":"12","low":"9","volume":"10000"}]', limit=48)
        self.assertEqual(sina[0].volume, 100)


class HttpTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.store = NotificationStore(Path(self.temp.name) / "events.db")
        self.market = MarketCache(demo=True)
        self.server = BridgeServer(("127.0.0.1", 0), "test-token", ["sh600519"], self.store, self.market)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.base = f"http://127.0.0.1:{self.server.server_port}"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=3)
        self.market.close()
        self.store.close()
        self.temp.cleanup()

    def request(self, path, token="test-token"):
        return urlopen(Request(self.base + path, headers={"Authorization": "Bearer " + token}), timeout=3)

    def test_unauthorized_and_query_token_rejected(self):
        for path, token in [("/api/state", "wrong"), ("/api/state?token=test-token", "")]:
            with self.assertRaises(HTTPError) as error:
                self.request(path, token)
            self.assertEqual(error.exception.code, 401)
            error.exception.close()

    def test_invalid_symbol_and_period_rejected(self):
        for path in ("/api/state?symbol=garbage", "/api/state?period=garbage"):
            with self.assertRaises(HTTPError) as error:
                self.request(path)
            self.assertEqual(error.exception.code, 400)
            error.exception.close()

    def test_watch_contract_utf8_no_secret_and_demo_label(self):
        self.store.ingest({"notifications": [event("real-event", 1)]})
        with self.request("/api/state") as response:
            payload = response.read().decode("utf-8")
            state = json.loads(payload)
            self.assertIn("charset=utf-8", response.headers["Content-Type"])
            self.assertEqual(response.headers["Cache-Control"], "no-store")
        self.assertIn("中文提醒", payload)
        self.assertNotIn("test-token", payload)
        self.assertTrue(state["demo"])
        self.assertEqual(state["symbols"], ["sh600519"])
        self.assertEqual(state["mcp"]["notifications"][0]["event_id"], "real-event")


class McpTests(unittest.IsolatedAsyncioTestCase):
    async def test_subscription_exit_surfaces_instead_of_hanging(self):
        with tempfile.TemporaryDirectory() as directory:
            store = NotificationStore(Path(directory) / "db")
            worker = McpWorker(store, "http://unused", "secret")
            class Session:
                async def read_resource(self, uri):
                    return SimpleNamespace(contents=[SimpleNamespace(text=json.dumps({"notifications": [event("pushed", 1)]}))])
            async def ended():
                raise ConnectionError("断线")
            watcher = asyncio.create_task(ended())
            try:
                with self.assertRaises(ConnectionError):
                    await asyncio.wait_for(worker._consume(Session(), "resource://notifications", asyncio.Queue(), watcher), timeout=2)
                self.assertEqual(store.snapshot()["notifications"][0]["event_id"], "pushed")
            finally:
                store.close()

    async def test_push_triggers_resource_refresh(self):
        with tempfile.TemporaryDirectory() as directory:
            store = NotificationStore(Path(directory) / "db")
            worker = McpWorker(store, "http://unused", "secret")
            updates = asyncio.Queue()
            class Session:
                count = 0
                async def read_resource(self, uri):
                    self.count += 1
                    if self.count == 1:
                        updates.put_nowait(uri)
                    else:
                        worker.stop_event.set()
                    return SimpleNamespace(contents=[SimpleNamespace(text=json.dumps({"notifications": [event(str(self.count), self.count)]}))])
            session = Session()
            task = asyncio.create_task(worker._consume(session, "resource://notifications", updates))
            try:
                # 第二次读入资源后取消等待，检查事件已即时存入。
                for _ in range(50):
                    if session.count >= 2:
                        break
                    await asyncio.sleep(.01)
                self.assertEqual(session.count, 2)
                self.assertEqual(len(store.snapshot()["notifications"]), 2)
            finally:
                task.cancel()
                with self.assertRaises(asyncio.CancelledError):
                    await task
                store.close()

    async def test_payload_supports_structured_and_text_and_errors(self):
        self.assertEqual(unpack(SimpleNamespace(structured_content={"resource_uri": "r"})), {"resource_uri": "r"})
        self.assertEqual(unpack(SimpleNamespace(content=[SimpleNamespace(text='{"resource_uri":"r"}')])), {"resource_uri": "r"})
        with self.assertRaises(ValueError):
            unpack(SimpleNamespace(isError=True))


if __name__ == "__main__":
    unittest.main()
