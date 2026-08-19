## 2025-05-20 - Flood guard query indexing
**Learning:** In `TipStore`, `countLivePending` executes on every tip request to enforce `MAX_LIVE_INVOICES`. Filtering by `tipper_chat_id = ? AND status = 'PENDING' AND expires_at >= ?` without a leading `tipper_chat_id` index resulted in scanning all live pending tips across the system.
**Action:** Always create composite index `idx_tips_tipper_status` on `tips(tipper_chat_id, status, expires_at)` to turn flood guard queries into O(log N) index seeks.
