# Today Headlines Design

## Goal

Add a “今日要闻” card above “盘面总结” on the Tools tab, showing a concise, source-labelled
selection of current market-moving public financial news rather than hard-coded chat output.

## Data Sources

Create a batched news fetcher that queries East Money’s public finance-news list first and
Sina’s public finance rolling-news endpoint as fallback. Every item records its actual source
and publication time. A failed source must never be presented as the other source.

Only items from the current local calendar day are eligible. If the current day has no result
after market close, show no items and say “暂无今日可用要闻”; never relabel older news as today.

## Selection

Create pure `HeadlineSelector.select(items: List<NewsItem>, nowMs: Long, limit: Int): List<NewsItem>`.

- Remove blank/duplicate titles and normalize source-specific whitespace.
- Rank macro/policy news first, then sector/industry news, then company events.
- Within each category prefer newer publication times.
- Preserve no more than five items for the card and twenty for the detail page.
- Classify titles with explicit keyword rules into `MACRO_POLICY`, `SECTOR_INDUSTRY`,
  `COMPANY_EVENT`, or `MARKET_GENERAL`.
- Do not infer bullish/bearish direction unless the title itself states a factual direction.

## UI And Refresh

- The first-level card is placed immediately above “盘面总结”.
- It shows last update time and five compact headlines with source, time, and category.
- “查看全部要闻” opens the existing report page with up to twenty items.
- “刷新” starts a background news task and prevents duplicate requests.
- During 08:30-22:00 local time, automatic refresh occurs at most once every 30 minutes.
- The Tools page’s existing render timer reads cached state and never performs network calls.
- Last valid items and timestamp persist across process restarts.
- On failure, retain the last valid list and show its timestamp; if none exists, show an empty
  state with a retry action.

## Copy Boundaries

Use “可能影响” or category labels only. Do not claim a headline guarantees a market or sector
move. Always show source and publication time so users can verify the item.
