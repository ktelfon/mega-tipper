## 2025-02-15 - Stream-based GZIP Decoding & Composite Flood-Guard Indexing

**Learning:**
1. High-frequency polling on TonAPI retrieves large JSON lists of account events (up to 50 events per request). Requesting `Accept-Encoding: gzip` reduces network payloads dramatically. Furthermore, parsing the input stream directly using Jackson's `ObjectMapper.readTree(InputStream)` avoiding raw `String` allocations reduces GC and heap pressure on the polling daemon.
2. In-memory flood guard limits are wiped on restart, so they are persisted in SQL. To avoid full table scans on the `tips` table when checking live pending counts per user, a composite index on `(tipper_chat_id, status, expires_at)` provides O(log N) constant-time seeks.

**Action:**
1. Always set `Accept-Encoding: gzip` on REST-polling clients and stream decode using `GZIPInputStream` direct to JSON parsers to bypass intermediate `String` memory buffers.
2. Ensure any SQL-based flood control or validation queries are backed by precise multi-column composite indices matching the `WHERE` criteria.
