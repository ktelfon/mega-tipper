# Bolt's Performance Journal

This journal tracks critical performance-obsessed learnings, bottlenecks, and optimizations specific to the TON Tip Bot codebase.

## 2024-11-20 - High-Frequency Polling GZIP Payload Compression
**Learning:** High-frequency polling (e.g., checking for new blockchain events every 10 seconds per address) can consume significant network bandwidth, leading to high data overhead, slower request completion times, and increased rate-limiting sensitivity. Modern JSON APIs (like TonAPI) support GZIP encoding out of the box, reducing payloads by 80-90%.
**Action:** Enable `Accept-Encoding: gzip` headers in the `TonApiClient` and conditionally decompress the response using `GZIPInputStream` only when the `Content-Encoding: gzip` header is present.

## 2024-11-20 - Flood-Guard Composite Index Optimization
**Learning:** The flood-guard verification query (`countLivePending`) frequently queries `tips` by `tipper_chat_id`, `status` ('PENDING'), and `expires_at` to check for spam. Without an index covering these exact query fields, the database is forced to perform full-table scans under write load, which degrades performance over time.
**Action:** Always verify frequently queried field paths for composite index coverage, and add `CREATE INDEX IF NOT EXISTS idx_tips_tipper_status_expiry ON tips(tipper_chat_id, status, expires_at)` to prevent database degradation under flood-guard checks.
