# Telegram TON Tip Bot — Plan

Non-custodial tipping bot. Money moves wallet-to-wallet; the backend only watches the
chain, validates payments, and notifies. No pooled funds, no custody.

**Current step: 3 — Telegram bot skeleton**

---

## Progress

- [x] **Step 0 — Blockchain verification spike**
- [x] **Step 1 — Address normalization**
- [x] **Step 2 — Persistence**
- [ ] **Step 3 — Telegram bot skeleton** ← current
- [ ] Step 4 — Tip request generation
- [ ] Step 5 — Wire poller to DB
- [ ] Step 6 — Production hardening
- [ ] Step 7 — File-selling extension

---

## Step 0 — Blockchain verification spike ✅ DONE

Prove a payment can be detected on-chain before building anything around it. Taken first
on purpose: least-trodden path on the JVM, and most likely to kill the design.

Delivered:
- TonAPI schema confirmed against the OpenAPI spec **and** live mainnet responses
- `TipMatcher.kt` — pure matching logic, 12 passing tests
- `TonVerifySpike.kt` — polling loop, verified end-to-end against a real mainnet transfer
  (event `a1cbe771…`, 10 TON, comment `"Staking"`)
- Working build toolchain (`./gradlew test`)

### Why the matcher is stricter than the original sketch

The first design matched on `comment == "tip_x" AND amount == 1.0`. Live mainnet data shows
that is genuinely exploitable — a text comment is public plaintext anyone can attach to any
transfer. Real examples found while testing:

- the identical comment `'@lemon 6000 ton'` twice, at the same amount
- a zero-value `"Claim your 1,000 TON airdrop from TON Foundation!"` dusting scam

So `TipMatcher` requires **all** of:

| Check | Stops |
|---|---|
| recipient is the creator's address | crediting a transfer that went elsewhere in the trace |
| `status == "ok"` | failed transactions, which move no money |
| exact amount, no tolerance | underpayment |
| exact comment, case + whitespace sensitive | near-miss spoofing |
| timestamp inside invoice window | **replay of a historical transfer with the same comment** |
| `is_scam == false` | dusting spam |
| `in_progress == false` | unsettled traces that can still change |
| `event_id` not already credited | double payout across polls/restarts |

Each check has a test named after the attack it stops.

---

## Step 1 — Address normalization ✅ DONE

Convert any address spelling → canonical raw `0:83df…`, with CRC16 validation.

**Why it was needed.** The first live Kotlin run found no match on a real 10 TON payment,
because TonAPI reports `0:83dfd552…` while the spike was passing `EQCD39…`. Same wallet,
different strings, never equal. A creator pasting the `UQ` form Tonkeeper shows would have
had every tip silently fail to confirm.

One wallet has five spellings — all the same workchain + 32-byte hash, differing only in a
flag byte that encodes bounceable-ness and network:

| Form | Tag | Meaning |
|---|---|---|
| `EQCD39…` | `0x11` | bounceable, mainnet |
| `UQCD39…` | `0x51` | non-bounceable, mainnet |
| `kQCD39…` | `0x91` | bounceable, testnet |
| `0QCD39…` | `0xD1` | non-bounceable, testnet |
| `0:83dfd552…` | — | raw, what TonAPI reports |

Delivered: `AddressNormalizer.kt`, 9 tests, wired into the spike. Both `EQ` and `UQ` forms
now match the same live mainnet payment.

Rejects, each with a user-facing reason safe to send as a Telegram reply:
- **failed checksum** — a typo'd address is a valid-looking string; tips sent there are gone
  for good, so refusing at `/setup` is the only protection
- **wrong network** — a testnet address on a mainnet bot would never receive anything
- **masterchain** (`wc != 0`) — validators and system contracts, not tip recipients

### ton4j gotcha

`Address.of()` signals bad input by throwing **`java.lang.Error`** ("Wrong crc16 hashsum",
"User-friendly address should contain strictly 48 characters"), not `Exception`. A plain
`catch (e: Exception)` lets malformed input escape — which is exactly how the first version
failed its own tests. `AddressNormalizer` catches `Throwable` and rethrows only
`VirtualMachineError`/`LinkageError`, so genuine JVM faults still surface.

---

## Step 2 — Persistence ✅ DONE

`Database.kt` (connection + schema) and `TipStore.kt` (repository), 14 contract tests run
against **both SQLite and Postgres**.

### The storage decision was made not to matter

Requirement: cloud-hosted, set up for many people, light data. The deciding factor turned out
not to be data volume but **filesystem durability** — many cloud hosts give you an ephemeral
filesystem, where a SQLite file is wiped on every redeploy, taking the creator wallet mappings
and the double-payout guard with it.

So the DDL is written to run unchanged on both engines, and switching is a connection-string
change:

```kotlin
Database.connect("jdbc:sqlite:/data/tipbot.db")                    // VPS with a volume
Database.connect("jdbc:postgresql://host/db", user, password)      // managed, ephemeral host
```

Portability rules the schema follows:
- `BIGINT` for ids/amounts/timestamps — Telegram chat ids exceed 32 bits, and SQLite gives
  `BIGINT` integer affinity, so it means the same thing on both
- epoch seconds, not `TIMESTAMP`, whose syntax and timezone handling diverge
- no `AUTOINCREMENT`/`SERIAL` — primary keys are values we generate (the nonce), sidestepping
  the biggest DDL difference between the engines
- `ON CONFLICT … DO UPDATE` for upserts — SQLite 3.24+, Postgres 9.5+

**Verified, not assumed:** the same 14-test contract runs green against SQLite *and* a real
Postgres 16 container. Without a server reachable the Postgres suite skips, so the build stays
green on a machine with no Docker.

### The double-payout guard

`UNIQUE INDEX ON tips(event_id)`. If the poller sees the same on-chain event twice — across a
restart, a retry, or two overlapping polls — the **database** refuses the second write, rather
than a check-then-write in application code that could interleave. `confirm()` returns `false`,
which means "do not notify, do not deliver". Both engines allow repeated `NULL`s there, so
unpaid invoices are unaffected.

Tested explicitly: confirming twice, one event credited to two different tips, and the guard
surviving a restart.

### Multi-creator by default

`creators` is keyed by `telegram_chat_id`, so one deployment serves as many people as you point
at it — no per-user instance needed.

### Schema

| `creators` | | | `tips` | |
|---|---|---|---|---|
| `telegram_chat_id` | BIGINT PK | | `nonce` | TEXT PK |
| `raw_address` | TEXT | | `creator_chat_id` / `tipper_chat_id` | BIGINT |
| `created_at` | BIGINT | | `raw_address`, `amount_nano` | TEXT / BIGINT |
| | | | `status` | PENDING / CONFIRMED / EXPIRED |
| | | | `event_id` | TEXT **UNIQUE** |
| | | | `sender_address` | TEXT |
| | | | `created_at`, `expires_at`, `confirmed_at` | BIGINT |

Nonces are `tip_` + 16 lowercase hex chars from `SecureRandom` — 64 bits, and lowercase so the
comment is unambiguous to retype given `TipMatcher` compares case-sensitively.

---

## Step 3 — Telegram bot skeleton

`telegrambots-spring-boot-starter`, or the lighter `kotlin-telegram-bot`.
`/setup` → validate + normalize + store the creator's address. First point where the bot is
something you can actually talk to.

---

## Step 4 — Tip request generation

- Nonce generation — **must** be `SecureRandom`, not sequential. A guessable nonce is a free tip.
- `ton://transfer?address=…&amount=…&text=<nonce>` deep link, surfaced as an inline button.

---

## Step 5 — Wire poller to DB

Promote the spike's loop into a background worker: poll each creator with a pending invoice,
on match mark `CONFIRMED`, fire the Telegram notification. Where step 0 stops being a spike.

---

## Step 6 — Production hardening

Webhooks instead of polling, rate limits, expiry sweeper for dead invoices.

Open decision: webhooks need a publicly reachable endpoint — a deployment problem, not a code
problem. Polling is fine until then.

---

## Step 7 — File-selling extension

Add `file_id` + price columns; on match call `sendDocument()` instead of only notifying.
Genuinely small once 0–5 exist.

---

## Running it

```bash
./gradlew test                 # 49 tests, all green
./gradlew run --args="<address> <comment> <amountTon> [timeoutSec] [pollSec]"
```

To include the Postgres contract tests (otherwise they skip):

```bash
docker run -d --rm --name tipbot-pg -e POSTGRES_PASSWORD=test -e POSTGRES_DB=tipbot \
  -p 55432:5432 postgres:16-alpine
./gradlew cleanTest test
```

Live example — any address form works now that step 1 is done:

```bash
TIP_LOOKBACK_SEC=999999999 ./gradlew run --args=\
"EQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqB2N Staking 10 25 5"
```

Env vars:

| Var | Default | Purpose |
|---|---|---|
| `TONAPI_BASE_URL` | `https://tonapi.io` | use `https://testnet.tonapi.io` for testnet |
| `TONAPI_KEY` | — | optional bearer token, raises the free rate limit |
| `TIP_LOOKBACK_SEC` | `300` | how far back the invoice window opens. **Testing only** — a narrow window in production is what stops replay |

### Environment note

You need a **JDK 21** — not just a JRE. On the original dev box the system JVM
(`/usr/lib/jvm/java-21-openjdk-amd64`) turned out to be a JRE with no `javac`, which surfaces
as a confusing Gradle toolchain error (*"Cannot find a Java installation matching
languageVersion=21"*) rather than an obvious "no compiler".

Any of these work — Gradle auto-detects all of them, so no path needs committing:
- `sudo apt install openjdk-21-jdk`
- SDKMAN: `sdk install java 21-tem`
- unpack a [Temurin JDK](https://adoptium.net) into `~/.jdks/` (Gradle scans that directory)

---

## Not done yet

- Not a git repo — `git init` whenever you want history
- No testnet run: everything was verified against live mainnet reads, and no transfer has
  ever been *sent* through this flow. The happy path is proven by observation, not by a
  payment we originated
