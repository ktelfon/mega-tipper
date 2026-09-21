# Bolt's Journal - Critical Learnings

## 2025-05-18 - Database Schema Indexing for Rate Limiting
**Learning:** `countLivePending` is queried on every tip creation command to enforce flood limits by checking `tipper_chat_id`, `status`, and `expires_at`. The existing index `idx_tips_status(status, expires_at)` did not include `tipper_chat_id` as a leading column, forcing queries filtering by `tipper_chat_id` to filter through all pending tips across all tippers as the table grows.
**Action:** Ensure per-user query rate limiters filtering on specific user IDs and status fields are backed by composite indexes matching `(user_id, status, ...)` for index-only scans.
