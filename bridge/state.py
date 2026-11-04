"""线程安全通知存储，游标和去重记录跨重启保留。"""
from __future__ import annotations

import json
import sqlite3
import threading
import uuid
from pathlib import Path
from typing import Any


class NotificationStore:
    def __init__(self, path: Path):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(str(path), check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, event_id TEXT UNIQUE, data TEXT NOT NULL)")
        self.db.execute("CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        self.db.execute("INSERT OR IGNORE INTO metadata(key,value) VALUES('stream_id',?)", (str(uuid.uuid4()),))
        self.stream_id = self.db.execute("SELECT value FROM metadata WHERE key='stream_id'").fetchone()[0]
        self.db.commit()
        self.status = "尚未连接 MCP"
        self.latest_sequence: int | None = None

    def set_status(self, value: str) -> None:
        with self.lock:
            self.status = value

    def ingest(self, payload: dict[str, Any]) -> int:
        rows = payload.get("notifications", [])
        if not isinstance(rows, list):
            raise ValueError("通知资源 notifications 必须为数组")
        valid = [r for r in rows if isinstance(r, dict) and str(r.get("event_id") or "").strip()]
        def sequence(row: dict) -> int:
            try:
                return max(0, int(row.get("sequence") or 0))
            except (TypeError, ValueError):
                return 0
        valid.sort(key=sequence)
        sequences = {sequence(r) for r in valid if sequence(r) > 0}
        try:
            latest = max(int(payload.get("latest_sequence") or 0), max(sequences, default=0))
        except (TypeError, ValueError):
            latest = max(sequences, default=0)
        with self.lock:
            previous = self.latest_sequence
            missing = 0
            if previous is not None and latest > previous:
                missing = max(0, latest - previous - sum(previous < s <= latest for s in sequences))
            if latest > 0:
                self.latest_sequence = latest
            for row in valid:
                clean = {key: str(row.get(key) or "") for key in ("event_id", "title", "body", "created_at", "priority", "event_type")}
                self.db.execute("INSERT OR IGNORE INTO events(event_id,data) VALUES(?,?)", (clean["event_id"], json.dumps(clean, ensure_ascii=False)))
            self.db.commit()
            return missing

    def snapshot(self) -> dict[str, Any]:
        with self.lock:
            rows = self.db.execute("SELECT id,data FROM events ORDER BY id DESC LIMIT 100").fetchall()
            return {
                "status": self.status,
                "stream_id": self.stream_id,
                "cursor": rows[0][0] if rows else 0,
                "notifications": [dict(json.loads(data), id=row_id) for row_id, data in rows],
            }

    def close(self) -> None:
        with self.lock:
            self.db.close()
