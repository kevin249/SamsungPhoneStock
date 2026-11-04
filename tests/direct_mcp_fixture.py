"""为手表原生集成测试提供本地 MCP 网关，不使用任何真实 Key。"""
import asyncio
import json

import uvicorn
from mcp.server import MCPServer
from mcp.server.subscriptions import InMemorySubscriptionBus, ResourceUpdated
from starlette.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

bus = InMemorySubscriptionBus()
mcp = MCPServer("watch-native-test", subscriptions=bus, log_level="ERROR")
uri = "watch-test://notifications"
payload = {"notifications": [{"event_id": "baseline", "sequence": 1, "title": "历史", "body": "测试历史"}], "latest_sequence": 1}
scheduled = False
automatic = True


@mcp.tool()
def get_notification_stream() -> dict:
    global scheduled
    scheduled = False
    payload["notifications"] = [{"event_id": "baseline", "sequence": 1, "title": "历史", "body": "测试历史"}]
    payload["latest_sequence"] = 1
    return {"resource_uri": uri}


async def push() -> None:
    await asyncio.sleep(.4)
    payload["notifications"].append({"event_id": "native-push", "sequence": 2, "title": "原生推送", "body": "手表直接接收 SSE"})
    payload["latest_sequence"] = 2
    await bus.publish(ResourceUpdated(uri=uri))
    await asyncio.sleep(.2)
    await bus.publish(ResourceUpdated(uri=uri))


@mcp.resource(uri)
async def notifications() -> str:
    global scheduled
    if automatic and not scheduled:
        scheduled = True
        asyncio.create_task(push())
    return json.dumps(payload, ensure_ascii=False)


app = mcp.streamable_http_app(host="127.0.0.1")


async def check_auth(request, call_next):
    if request.headers.get("authorization") != "Bearer fixture-only":
        return JSONResponse({"error": "fixture authentication required"}, status_code=401)
    if request.url.path == "/test/reset":
        global automatic, scheduled
        automatic = False
        scheduled = False
        payload["notifications"] = [{"event_id": "baseline", "sequence": 1, "title": "历史", "body": "测试历史"}]
        payload["latest_sequence"] = 1
        return JSONResponse({"ok": True})
    if request.url.path == "/test/push":
        async def delayed():
            await asyncio.sleep(5)
            row = {"event_id": "background-push", "sequence": 2, "title": "后台接收测试", "body": "熄屏收到新消息并震动"}
            if not any(item["event_id"] == row["event_id"] for item in payload["notifications"]):
                payload["notifications"].append(row)
            payload["latest_sequence"] = 2
            await bus.publish(ResourceUpdated(uri=uri))
            await asyncio.sleep(1)
            await bus.publish(ResourceUpdated(uri=uri))
        asyncio.create_task(delayed())
        return JSONResponse({"ok": True})
    return await call_next(request)


app.add_middleware(BaseHTTPMiddleware, dispatch=check_auth)


if __name__ == "__main__":
    uvicorn.run(app, host="127.0.0.1", port=9876, log_level="error")
