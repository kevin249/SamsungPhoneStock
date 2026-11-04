"""用官方 MCP SDK 启动本地网关，验证真实 HTTP 建连、资源订阅和推送。"""
from __future__ import annotations

import asyncio
import json
import logging
import socket
import tempfile
import unittest
from contextlib import suppress
from pathlib import Path

import uvicorn
from mcp.server import MCPServer
from mcp.server.subscriptions import InMemorySubscriptionBus, ResourceUpdated

from bridge.mcp_worker import McpWorker
from bridge.state import NotificationStore


class McpHttpTests(unittest.IsolatedAsyncioTestCase):
    async def test_discovery_subscription_push_and_cancellation_over_real_http(self):
        logging.getLogger("httpx2").setLevel(logging.WARNING)
        bus = InMemorySubscriptionBus()
        mcp = MCPServer("watch-test", subscriptions=bus, log_level="ERROR")
        uri = "watch://notifications"
        payload = {"notifications": [], "latest_sequence": 0}

        @mcp.tool()
        def get_notification_stream() -> dict:
            return {"resource_uri": uri}

        @mcp.resource(uri)
        def notifications() -> str:
            return json.dumps(payload, ensure_ascii=False)

        listener = socket.socket()
        listener.bind(("127.0.0.1", 0))
        port = listener.getsockname()[1]
        app = mcp.streamable_http_app(host="127.0.0.1")
        server = uvicorn.Server(uvicorn.Config(app, log_level="error", lifespan="on", timeout_graceful_shutdown=2))
        serving = asyncio.create_task(server.serve(sockets=[listener]))
        with tempfile.TemporaryDirectory() as directory:
            store = NotificationStore(Path(directory) / "events.db")
            worker = McpWorker(store, f"http://127.0.0.1:{port}/mcp", "fixture-only")
            session = None
            try:
                for _ in range(100):
                    if server.started:
                        break
                    await asyncio.sleep(.02)
                self.assertTrue(server.started)
                session = asyncio.create_task(worker._session())
                for _ in range(200):
                    if session.done():
                        await session
                    if store.snapshot()["status"] == "MCP 已连接":
                        break
                    await asyncio.sleep(.02)
                self.assertEqual(store.snapshot()["status"], "MCP 已连接")
                payload["notifications"] = [{"event_id": "http-push-1", "sequence": 1, "title": "真实订阅测试", "body": "由本地测试网关通过 SSE 推送"}]
                payload["latest_sequence"] = 1
                await bus.publish(ResourceUpdated(uri=uri))
                for _ in range(100):
                    if store.snapshot()["notifications"]:
                        break
                    await asyncio.sleep(.02)
                received = store.snapshot()["notifications"]
                self.assertEqual(len(received), 1)
                self.assertEqual(received[0]["event_id"], "http-push-1")
                # 重复推送不能重复存储，也不能推进手表游标。
                cursor = store.snapshot()["cursor"]
                await bus.publish(ResourceUpdated(uri=uri))
                await asyncio.sleep(.1)
                self.assertEqual(store.snapshot()["cursor"], cursor)
            finally:
                if session is not None:
                    session.cancel()
                    with suppress(asyncio.CancelledError):
                        await asyncio.wait_for(session, timeout=4)
                server.should_exit = True
                await asyncio.wait_for(serving, timeout=5)
                listener.close()
                store.close()


if __name__ == "__main__":
    unittest.main()
