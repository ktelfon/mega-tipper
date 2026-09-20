## 2025-05-18 - Pre-computing friendly address in single-owner CommandHandler
**Learning:** In a single-owner architecture where the owner wallet address is fixed at startup, pre-computing formatted representation strings during component instantiation avoids redundant address conversions without the memory or allocation risks of global caches.
**Action:** Prefer instance-level caching/pre-computation for fixed configuration parameters instead of global static caches when inputs are static per instance.
