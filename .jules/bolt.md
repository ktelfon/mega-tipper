# Bolt's Journal - Critical Learnings

## 2025-05-10 - Maven Central Dependency Mirroring
**Learning:** Maven Central rate limits (429 Too Many Requests) can break `./gradlew test` in build environments.
**Action:** Always configure Google Cloud or Repo1 Maven Central mirrors in `settings.gradle.kts` and `build.gradle.kts` if 429s occur.

## 2025-05-10 - Indexing Flood Guard Query for High Concurrency
**Learning:** The flood guard query `SELECT COUNT(*) FROM tips WHERE tipper_chat_id = ? AND status = 'PENDING' AND expires_at >= ?` is run on every incoming user command/button interaction. Without an index covering `tipper_chat_id`, SQLite and Postgres perform a full table scan over all historical tips.
**Action:** Use a composite index on `(tipper_chat_id, status, expires_at)` to keep flood check lookups O(log N) as the `tips` table grows.
