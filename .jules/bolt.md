## 2026-10-01 - Flood guard query indexing
**Learning:** In `TipStore.countLivePending`, checking a user's active invoice count (`tipper_chat_id = ? AND status = 'PENDING' AND expires_at >= ?`) runs on every tip creation attempt. Without a index covering `tipper_chat_id`, database engines perform index scans over all pending tips in the system.
**Action:** Always complement generic status indices with user/tipper composite indices `(tipper_chat_id, status, expires_at)` when user-scoped query filters are used on high-throughput handler paths.
