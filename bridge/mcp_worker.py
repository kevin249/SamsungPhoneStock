"""MCP 独立长连接：新版 listen、旧版 subscribe、断线重连和低频补漏。"""
from __future__ import annotations

import asyncio
import json
import logging
import threading
from contextlib import suppress
from typing import Any

from .state import NotificationStore

LOG = logging.getLogger(__name__)


def unpack(result: Any, resource: bool = False) -> dict:
    if getattr(result, "is_error", getattr(result, "isError", False)):
        raise ValueError("MCP 返回工具错误")
    structured = getattr(result, "structured_content", None)
    if structured is None:
        structured = getattr(result, "structuredContent", None)
    if isinstance(structured, dict):
        return structured
    for item in getattr(result, "contents" if resource else "content", ()):
        text = getattr(item, "text", None)
        if isinstance(text, str):
            with suppress(ValueError):
                payload = json.loads(text)
                if isinstance(payload, dict):
                    return payload
    raise ValueError("MCP 返回格式无效")


class McpWorker:
    def __init__(self, store: NotificationStore, url: str, api_key: str):
        self.store, self.url, self.api_key = store, url, api_key
        self.stop_event = threading.Event()
        self.thread: threading.Thread | None = None
        self.loop: asyncio.AbstractEventLoop | None = None
        self.task: asyncio.Task | None = None

    def start(self) -> None:
        if not self.url:
            self.store.set_status("未配置 GUPIAO_MCP_URL")
            return
        if not self.api_key:
            self.store.set_status("未配置 GUPIAO_MCP_API_KEY")
            return
        self.thread = threading.Thread(target=self._entry, daemon=True, name="mcp-subscription")
        self.thread.start()

    def _entry(self) -> None:
        asyncio.run(self._run())

    async def _run(self) -> None:
        self.loop = asyncio.get_running_loop()
        self.task = asyncio.current_task()
        try:
            while not self.stop_event.is_set():
                self.store.set_status("正在连接 MCP")
                try:
                    await self._session()
                except asyncio.CancelledError:
                    raise
                except Exception as exc:
                    # 不记录可能带 API Key 或内部请求头的异常正文。
                    self.store.set_status(f"MCP 断开 · {type(exc).__name__} · 3 秒后重连")
                    LOG.warning("MCP disconnected: %s", type(exc).__name__)
                await asyncio.sleep(3)
        except asyncio.CancelledError:
            pass

    async def _session(self) -> None:
        import httpx2
        from mcp import ClientSession, types
        from mcp.client.streamable_http import streamable_http_client
        from mcp.client.subscriptions import ResourceUpdated, listen
        from mcp.shared.exceptions import MCPError

        updates: asyncio.Queue[str] = asyncio.Queue(maxsize=1)

        async def message_handler(message: Any) -> None:
            if isinstance(message, types.ResourceUpdatedNotification) and updates.empty():
                updates.put_nowait(str(message.params.uri))

        async with httpx2.AsyncClient(headers={"Authorization": f"Bearer {self.api_key}"}, timeout=httpx2.Timeout(30, read=300)) as client:
            async with streamable_http_client(self.url, http_client=client, terminate_on_close=False) as (reader, writer):
                async with ClientSession(reader, writer, message_handler=message_handler, read_timeout_seconds=15) as session:
                    modern = True
                    try:
                        await session.discover()
                    except MCPError as exc:
                        if exc.code not in {-32601, -32602}:
                            raise
                        modern = False
                        await session.initialize()
                    info = unpack(await session.call_tool("get_notification_stream"))
                    uri = str(info.get("resource_uri") or "")
                    if not uri:
                        raise ValueError("缺少通知 resource_uri")
                    if modern:
                        async with listen(session, resource_subscriptions=[uri]) as subscription:
                            if uri not in tuple(subscription.honored.resource_subscriptions or ()):
                                raise ValueError("服务器未确认订阅")
                            async def watch() -> None:
                                async for event in subscription:
                                    if isinstance(event, ResourceUpdated) and str(event.uri) == uri and updates.empty():
                                        updates.put_nowait(uri)
                                raise ConnectionError("订阅已结束")
                            watcher = asyncio.create_task(watch())
                            try:
                                await self._consume(session, uri, updates, watcher)
                            finally:
                                watcher.cancel()
                                with suppress(asyncio.CancelledError, Exception):
                                    await watcher
                    else:
                        await session.subscribe_resource(uri)
                        await self._consume(session, uri, updates)

    async def _consume(self, session: Any, uri: str, updates: asyncio.Queue, watcher: asyncio.Task | None = None) -> None:
        while not self.stop_event.is_set():
            missing = self.store.ingest(unpack(await session.read_resource(uri), resource=True))
            self.store.set_status(f"MCP 已连接 · 有 {missing} 条历史消息无法补回" if missing else "MCP 已连接")
            pending = asyncio.create_task(updates.get())
            try:
                waiters = {pending} if watcher is None else {pending, watcher}
                done, _ = await asyncio.wait(waiters, timeout=60, return_when=asyncio.FIRST_COMPLETED)
                if watcher is not None and watcher in done:
                    await watcher
                if pending in done and pending.result() != uri:
                    continue
            finally:
                pending.cancel()
                with suppress(asyncio.CancelledError):
                    await pending

    def stop(self) -> None:
        self.stop_event.set()
        if self.loop is not None and self.task is not None:
            with suppress(RuntimeError):
                self.loop.call_soon_threadsafe(self.task.cancel)
        if self.thread is not None:
            self.thread.join(timeout=5)
