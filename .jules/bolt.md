# Bolt's Journal - Critical Learnings

## 2023-11-20 - [TonAPI Event Payloads & Compression]
**Learning:** Polling a high-limit account events endpoint from TonAPI (with `limit=50` by default) returns substantial JSON payloads. Under frequent polling (every 10 seconds), this can consume close to ~860MB of network bandwidth per day per address. Enabling GZIP compression can transparently reduce the network footprint by 75-80% (~130MB per day) and improve packet-handling latency.
**Action:** Always negotiate `Accept-Encoding: gzip` for frequent poller HTTP client endpoints, and handle the content decompression if GZIP is returned.

## 2023-11-20 - [Stateless Flood Guard Query Optimization]
**Learning:** The stateless flood-guard counts pending tips via a SQL query: `SELECT COUNT(*) FROM tips WHERE tipper_chat_id = ? AND status = 'PENDING' AND expires_at >= ?`. Without an index starting with `tipper_chat_id`, this query is prone to full table scans as the history of tips grows, leading to performance bottlenecks under concurrent chat activity.
**Action:** Add a composite index on `(tipper_chat_id, status, expires_at)` to keep flood-guard queries running in O(1) time.

## 2023-11-20 - [Redundant Address Normalization in Command Parsing]
**Learning:** Address parsing/formatting via `AddressNormalizer` is relatively expensive because of base64 and hex conversions. Calling it on every single Telegram command or callback update to render the wallet address is a CPU waste since the owner's address is fixed for the lifetime of the bot.
**Action:** Cache the formatted user-friendly address string at instantiation to avoid repeating the parsing on hot paths.
