# 华尔街见闻「只看重要」快讯接入归档

> 目的：把本项目已验证的华尔街见闻 7×24 快讯接入方式抽成可迁移说明，供其他项目复用。
>
> 记录日期：2026-10-28

## 适用范围

适合用作：

- 财经应用的“今日要闻”候选来源；
- AI 分析时的近期事件上下文；
- 持仓复盘中的新闻线索补充。

不适合直接用作：

- 财报、估值或基本面分析的事实来源；
- 交易所公告或监管披露的替代品；
- 自动交易、买卖方向或利好/利空结论的唯一依据。

华尔街见闻快讯是媒体内容。即使接口将一条信息标记为重要，也不表示它对某只股票必然利好或利空。

## 已验证的请求

```http
GET https://api-one.wallstcn.com/apiv1/content/lives?channel=global-channel&limit=50&score=2
Referer: https://wallstreetcn.com/live/global
User-Agent: Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36
```

关键参数：

| 参数 | 含义 | 结论 |
| --- | --- | --- |
| `channel=global-channel` | 全局 7×24 快讯频道 | 已验证可用 |
| `limit=50` | 返回条数上限 | 已验证可用 |
| `score=2` | “只看重要”筛选 | 已验证可用 |

接口成功时响应顶层为：

```json
{
  "code": 20000,
  "message": "OK",
  "data": {
    "items": [],
    "next_cursor": "...",
    "polling_cursor": "..."
  }
}
```

### 不应使用的参数

以下参数曾测试为**没有筛选效果**，服务端会返回与默认请求相同的结果：

```text
important=true
is_important=true
only_important=true
min_score=2
importance=2
```

因此应使用 `score=2`，并在客户端再次确认响应行的 `score == 2`，防止上游日后静默忽略查询参数。

## 主要响应字段

`data.items[]` 中当前可用于新闻展示和溯源的字段：

| 字段 | 用途 | 说明 |
| --- | --- | --- |
| `id` | 去重或增量游标辅助 | 快讯 ID |
| `title` | 优先显示标题 | 部分 live 条目为空 |
| `content_text` | 标题为空时的后备文本 | 适合压缩为空白规范化后的单行标题 |
| `display_time` | 发布时间 | Unix **秒级**时间戳 |
| `uri` | 原文链接 | 例如 `https://wallstreetcn.com/livenews/<id>` |
| `channels` | 原始频道标签 | 可保留为分类/检索辅助信息 |
| `score` | 重要级别 | 此接入只接受值为 `2` 的项目 |
| `fund_codes` / `symbols` | 可能存在的关联字段 | 当前不应假设每条均有值或可用于股票匹配 |

建议的标题回退顺序：

```text
title → highlight_title → content_text（合并空白并截断）
```

`display_time` 需乘以 `1_000` 后转为毫秒时间戳。

## 可复用 Kotlin 实现

下面示例只依赖项目内已有的 `Http.getText` 与 `org.json`。可替换为 OkHttp、Retrofit 或 Ktor。

```kotlin
private const val WALLSTREETCN_IMPORTANT_URL =
    "https://api-one.wallstcn.com/apiv1/content/lives?" +
        "channel=global-channel&limit=50&score=2"

data class ImportantNews(
    val id: Long,
    val title: String,
    val publishedAtMs: Long,
    val url: String?,
    val channels: List<String>,
)

fun fetchWallstreetcnImportantNews(): List<ImportantNews> {
    val text = Http.getText(
        url = WALLSTREETCN_IMPORTANT_URL,
        referer = "https://wallstreetcn.com/live/global",
    )
    val root = JSONObject(text)
    require(root.optInt("code") == 20000) {
        "华尔街见闻快讯返回失败：${root.optString("message", "未知错误")}" 
    }
    val rows = root.optJSONObject("data")?.optJSONArray("items") ?: JSONArray()

    return buildList {
        for (index in 0 until rows.length()) {
            val row = rows.optJSONObject(index) ?: continue
            if (row.optInt("score") != 2) continue

            val fallback = row.optString("content_text")
                .replace(Regex("\\s+"), " ")
                .trim()
            val title = row.optString("title").trim()
                .ifBlank { row.optString("highlight_title").trim() }
                .ifBlank { fallback.take(220) }
            val timeSeconds = row.optLong("display_time")
            if (title.isBlank() || timeSeconds <= 0L) continue

            val channels = row.optJSONArray("channels")?.let { array ->
                (0 until array.length()).mapNotNull { item ->
                    array.optString(item).trim().takeIf(String::isNotBlank)
                }
            }.orEmpty()

            add(
                ImportantNews(
                    id = row.optLong("id"),
                    title = title,
                    publishedAtMs = timeSeconds * 1_000L,
                    url = row.optString("uri").trim().takeIf(String::isNotBlank),
                    channels = channels,
                ),
            )
        }
    }.sortedByDescending { it.publishedAtMs }
}
```

## 推荐的集成边界

### 今日要闻

将华尔街见闻作为投资新闻的首选展示来源，但保留其他新闻源作为补充和交叉印证。

不要把“首选”实现为无条件覆盖：持仓关联度、事件实质性和新鲜度仍应优先于来源偏好。对于同一事件的多来源重复内容，可优先显示华尔街见闻版本，同时保留其他来源为“多源印证”。

本项目的实现位置：

- `core/MarketNewsClient.kt`：接口地址、请求、解析和 `score == 2` 二次校验；
- `analysis/Headlines.kt`：来源优先级与多源聚类；
- `core/MarketNewsJson.kt`：选中要闻的落盘和恢复。

### AI 综合分析

AI 综合分析开始时主动请求一次重要快讯，并作为带时间、链接和来源的上下文传给模型。

模型提示应限制为：

1. 仅在快讯与用户问题相关时引用；
2. 引用时注明“华尔街见闻”和发布时间；
3. 未匹配到某标的，不得写成该标的“没有事件”；
4. 重要快讯只作事件线索，重大事项应尽量交叉验证公司公告、交易所或监管来源。

本项目的实现位置：

- `monitor/AnalysisService.kt`：AI 任务启动时主动拉取；
- `core/AiAnalysis.kt`：将快讯写入模型上下文和使用约束。

### 持仓复盘

复盘每次只请求一次近两自然日的重要快讯，再在本地按实际持仓名称和六码股票代码匹配。

推荐输出为独立子段：

```text
华尔街见闻重要快讯（本地关键词匹配，score=2，非交易所公告）
```

必须与交易所公告分开：

- 交易所公告可以在有明确规则时分类为重大利好或重大利空；
- 华尔街见闻快讯只作为待核查的媒体线索，不自动定性；
- 快讯接口失败不得阻断复盘，报告应写“暂缺”；
- 没有本地匹配结果应写“未匹配到，不代表没有相关事件”。

本项目的实现位置：

- `monitor/HoldingAnalyzer.kt`：每份复盘单次获取快讯；
- `analysis/DailyReview.kt`：名称/代码本地匹配和独立展示。

## 本地匹配建议

接口没有被验证出可靠的“按股票关键词搜索”请求参数。因此推荐批量取得 `score=2` 快讯后，在客户端匹配：

```kotlin
fun matchesHolding(news: ImportantNews, code: String, name: String): Boolean {
    fun normalize(value: String) = value
        .lowercase(Locale.CHINA)
        .replace(Regex("[\\s\\p{Punct}，。！？；：、“”‘’（）【】《》]+"), "")

    val text = normalize(news.title + " " + news.channels.joinToString(" "))
    val nameToken = normalize(name)
    val codeToken = code.takeLast(6)
    return (nameToken.length >= 2 && text.contains(nameToken)) ||
        (codeToken.length == 6 && text.contains(codeToken))
}
```

匹配是关键词命中，不是证券实体识别。名称较短、简称歧义或一条快讯同时涉及多家公司时，必须在界面中保留原文链接供人工核对。

## 稳定性与合规边界

- 这是已验证可访问的公开接口，不是已确认的正式开放 API，参数、字段和访问策略可能变更。
- 请求应设置连接和读取超时，并把单源失败降级为部分失败，不应让新闻、AI 或复盘主流程整体失败。
- 控制请求频率。持仓复盘或 AI 一次任务中只请求一次，不要按每只股票重复调用。
- 不要把媒体快讯原文大规模保存、重新发布或作为唯一投资建议依据。使用前应自行确认华尔街见闻的服务条款和内容使用要求。
- 任何重大交易、监管、业绩或公司事件，应尽可能以公司公告、交易所或监管披露交叉验证。
