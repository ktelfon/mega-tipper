# Bolt's Journal

## 2026-10-24 - Optimization of API payload parsing and Database Flood-guard Verification
**Learning:** Parsing JSON API response bodies as string before passing them to Jackson causes large allocations and GC pressure, which can be avoided by stream-parsing. Adding a `Content-Encoding: gzip` request header and handling with `GZIPInputStream` minimizes both network transit overhead and memory footprints. On the database side, when doing frequent flood-guard count queries using multiple query parameters (e.g., `tipper_chat_id`, `status`, `expires_at`), a composite index on all three filter columns prevents full/partial table scans completely, making database-driven rate-limiting exceptionally fast.
**Action:** Always stream-parse raw InputStreams instead of mapping full strings in JSON HTTP clients, configure compression headers, and design precise composite database indices that match multi-column filters perfectly.
