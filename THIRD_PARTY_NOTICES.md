# 参考与复用

本项目参考 [kevin249/wenzidingpan](https://github.com/kevin249/wenzidingpan)，
固定参考提交 `d7daed9bf24ac84912c29ff2a10b448703b0b5fe`。
该提交 README 的「许可」章节声明 MIT；上游当时未提供独立 LICENSE 文件。

以下文件直接复用并保留原文件内容：

- `bridge/vendor/kline.py` ← `stockwidget/kline.py`
- `bridge/vendor/symbols.py` ← `stockwidget/symbols.py`
- `bridge/vendor/providers/base.py` ← `stockwidget/providers/base.py`

`bridge/vendor/config.py` 是仅含 `KLINE_SOURCES` 的适配文件。
MCP 资源发现、订阅及序号自检流程参考上游 `stockwidget/mcp_notifications.py`。
0.1.0 的电脑桥接代码保留供回归参考；0.2.0 的 `McpDirectClient.java` 将订阅移植到手表原生 HTTP 客户端，
`MarketDirectClient.java` 根据上游行情取数规则移植三源 K 线与失败降级。
`StockSearchClient.java` 参考上游 `stockwidget/search.py` 的腾讯/东方财富搜索及证券过滤规则。
手表界面为本项目新增的原生 Android 界面。
