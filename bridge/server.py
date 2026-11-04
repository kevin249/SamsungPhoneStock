"""标准库 HTTP 服务；仅向持有独立手表令牌的客户端开放数据。"""
from __future__ import annotations

import argparse
import hmac
import json
import logging
import os
import secrets
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from .market import MarketCache, PERIODS, now
from .mcp_worker import McpWorker
from .state import NotificationStore
from .vendor.symbols import classify

ROOT = Path(__file__).resolve().parent.parent


class BridgeServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address: tuple[str, int], token: str, symbols: list[str], store: NotificationStore, market: MarketCache):
        super().__init__(address, Handler)
        self.token, self.symbols, self.store, self.market = token, symbols, store, market


class Handler(BaseHTTPRequestHandler):
    server: BridgeServer

    def do_GET(self) -> None:
        parsed = urlsplit(self.path)
        auth = self.headers.get("Authorization", "")
        if not hmac.compare_digest(auth.encode("utf-8"), f"Bearer {self.server.token}".encode("utf-8")):
            self.reply(401, {"error": "手表连接令牌不正确"})
            return
        if parsed.path == "/api/health":
            self.reply(200, {"ok": True, "demo": self.server.market.demo, "mcp": self.server.store.snapshot()["status"]})
            return
        if parsed.path != "/api/state":
            self.reply(404, {"error": "接口不存在"})
            return
        query = parse_qs(parsed.query)
        symbol = query.get("symbol", [self.server.symbols[0]])[0]
        period = query.get("period", ["1d"])[0]
        if symbol not in self.server.symbols or period not in PERIODS:
            self.reply(400, {"error": "请选择已配置的股票和支持的周期"})
            return
        self.reply(200, {
            "version": 1,
            "server_time": now(),
            "symbols": self.server.symbols,
            "periods": PERIODS,
            "demo": self.server.market.demo,
            "chart": self.server.market.snapshot(symbol, period),
            "mcp": self.server.store.snapshot(),
        })

    def reply(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False, allow_nan=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def log_message(self, fmt: str, *args: object) -> None:
        # 不记录 URL、令牌或请求头。
        logging.getLogger(__name__).debug("HTTP %s", args[1] if len(args) > 1 else "")


def load_token(runtime: Path) -> str:
    configured = os.environ.get("WATCH_BRIDGE_TOKEN", "").strip()
    path = runtime / "watch-token.txt"
    if configured:
        return configured
    if path.exists():
        value = path.read_text(encoding="utf-8").strip()
        if value:
            return value
    token = secrets.token_urlsafe(18)
    path.write_text(token, encoding="utf-8")
    return token


def main() -> None:
    parser = argparse.ArgumentParser(description="腕上盯盘电脑桥接")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--demo", action="store_true", help="仅使用明确标注的模拟行情和通知")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    runtime = ROOT / ".runtime"
    runtime.mkdir(exist_ok=True)
    symbols = list(dict.fromkeys(s.key for raw in os.environ.get("WATCH_SYMBOLS", "600519,000001,300750,601318").split(",") if (s := classify(raw))))[:6]
    if not symbols:
        parser.error("WATCH_SYMBOLS 至少需要一个有效股票代码")
    token = load_token(runtime)
    # 演示与真实通知数据库隔离，避免模拟消息进入实盘列表。
    store = NotificationStore(runtime / ("demo-events.sqlite3" if args.demo else "events.sqlite3"))
    market = MarketCache(demo=args.demo)
    worker = McpWorker(store, os.environ.get("GUPIAO_MCP_URL", "").strip(), os.environ.get("GUPIAO_MCP_API_KEY", "").strip())
    server = BridgeServer((args.host, args.port), token, symbols, store, market)
    if args.demo:
        store.ingest({"notifications": [{"event_id": "demo-1", "title": "演示 · MCP 通知", "body": "这里显示 MCP 提醒正文。向右滑动返回 K 线。此消息为模拟数据。", "created_at": now(), "sequence": 1}], "latest_sequence": 1})
        store.set_status("演示模式 · 未连接真实 MCP")
    else:
        worker.start()
    print(f"腕上盯盘已启动，端口 {args.port}。手表令牌保存在 .runtime/watch-token.txt（或 WATCH_BRIDGE_TOKEN）。", flush=True)
    try:
        addresses = sorted({info[4][0] for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)})
    except OSError:
        addresses = []
    for address in addresses:
        print(f"手表可尝试连接 http://{address}:{args.port}（选择与手表同一网络的地址）", flush=True)
    try:
        server.serve_forever(poll_interval=.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        worker.stop()
        market.close()
        store.close()


if __name__ == "__main__":
    main()
