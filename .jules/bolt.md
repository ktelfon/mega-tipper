# Bolt's Journal

Critical learnings and performance patterns in this codebase.

## 2025-05-18 - TipStore PreparedStatement & Connection Reuse
**Learning:** SQLite and Postgres query execution overhead can be reduced by optimizing repetitive database reads/writes in hot paths like `TipStore.pendingTips` and `TipStore.countLivePending`.
**Action:** Always measure DB call profiles and minimize allocations during polling loops.
