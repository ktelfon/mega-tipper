# Bolt's Journal

## 2025-05-18 - Composite Index for Tipper Limit Queries
**Learning:** In `TipStore.countLivePending`, queries filter by `tipper_chat_id`, `status = 'PENDING'`, and `expires_at >= now`. An index on `(status, expires_at)` alone forces a scan across all pending tips for all tippers to evaluate `tipper_chat_id`. A composite index on `(tipper_chat_id, status, expires_at)` provides index-seeking directly to the specific tipper's pending records, preventing O(N) index scans on every `/tip` request or button tap as total pending tips scale.
**Action:** Always verify composite indexing on filtered table queries that run on high-frequency user interactions (like flood guards and rate limiters).
