# Bolt's Journal - Critical Learnings

## 2025-05-18 - Caching friendly address string in OwnerConfig / CommandHandler
**Learning:** `AddressNormalizer.toUserFriendly()` relies on `org.ton.ton4j.address.Address.of()` which involves Base64 decoding and CRC16 calculation. In single-owner setup (`OwnerConfig`), generating the friendly address on every command handling or invoice issuance causes repeated address parsing overhead.
**Action:** Pre-compute or lazily compute the friendly address format for the configured owner address once at initialization.
