# Telegram TON Tip Bot — Plan

Non-custodial tipping bot. Money moves wallet-to-wallet; the backend only watches the
chain, validates payments, and notifies. No pooled funds, no custody.

**Current step: 6 — production hardening**

---

## Progress

- [x] **Step 0 — Blockchain verification spike**
- [x] **Step 1 — Address normalization**
- [x] **Step 2 — Persistence**
- [x] **Step 3 — Telegram bot skeleton**
- [x] **Step 4 — Tip request generation**
- [x] **Step 5 — Wire poller to DB**
- [ ] **Step 6 — Production hardening** ← current
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

## Step 3 — Telegram bot skeleton ✅ DONE

`telegrambots-longpolling` 9.0.0 — long polling needs no public URL, so the bot runs the same
on a laptop as in the cloud. `TipBot.kt` is deliberately thin plumbing; every decision about
what to say lives in `CommandHandler`, which is pure and tested without a token or a network.

Commands: `/start`, `/help`, `/setup`, `/wallet`, `/link`, `/tip`.

**Stateless on purpose.** There is no "awaiting input" flag anywhere. `/setup` followed by a
bare address works because any message parsing as a TON address is a registration, and the
tipping flow carries the creator's id in the deep link and the button payload. Nothing is lost
on restart, and a redeploy cannot strand a half-finished conversation.

---

## Step 4 — Tip request generation ✅ DONE

Creator gets `https://t.me/<bot>?start=<chatId>` to share. A tipper opening it lands on an
amount menu; tapping one issues the invoice and returns a payment link. `/tip <amount>` bills
your own wallet, which is how the whole flow gets tested without a second account.

### Two links per invoice, because they are consumed differently

Telegram rejects inline-button URLs whose scheme is not http(s) or tg, so a `ton://` link
**cannot be a button**. The `ton://transfer/…` URI goes in the message body for any wallet;
the button gets the `https://app.tonkeeper.com/transfer/…` universal link.

### Non-bounceable addresses in payment links

A bounceable transfer to a wallet that has never sent a transaction — so its contract is not
deployed — is returned to the sender minus fees. That is exactly the creator who just
installed a wallet to collect tips, so the bounceable form would fail precisely for new users.
`AddressNormalizer.toUserFriendly()` renders `UQ`/`0Q`.

### Amounts are BigDecimal, never Double

`TipMatcher` compares the amount exactly, with no tolerance. `0.1 + 0.2` in binary floating
point is not `0.3`, and one bit of rounding error produces an invoice that a correct payment
can never satisfy — the money leaves the tipper's wallet and the tip never confirms. `1e9` is
refused rather than read as a billion TON, sub-nanoTON precision is refused as unpayable, and
amounts outside 0.01–10 000 TON are refused as dust or a fat-fingered digit.

---

## Step 5 — Wire poller to DB ✅ DONE

Where step 0 stops being a spike. `TipPoller` runs on a daemon thread beside the bot -
confirming a payment is driven by the blockchain, not by anyone sending a message, so it
cannot live in the update loop. `TonApiClient` is now shared by the poller and the spike, so
there is one request path rather than two that can drift.

`pollOnce()` is a single pass returning what it confirmed, so all 16 tests drive it against
canned TonAPI responses — no network, no sleeping, no clock.

### What one pass does

1. sweep expired invoices, so dead ones stop being watched
2. read pending tips, **grouped by address** — one request per creator, not per tip, because
   two tippers at once is the normal case and must not double the call rate
3. `TipMatcher` against each invoice, unchanged from step 0
4. `store.confirm()`, and **only if it returns true**, notify

### Failure rules, each tested

| Situation | Behaviour | Why |
|---|---|---|
| 429 rate limit | end the pass immediately | walking the remaining creators into the same wall helps nobody; the next cycle's sleep is the back-off |
| one creator's lookup fails | log, carry on with the others | an unrelated creator's timeout must not stall everyone's confirmations |
| any exception in a cycle | log, keep the loop alive | the worker must outlive a bad response, or every future payment silently stops confirming |
| `confirm()` returns false | log, do **not** notify | the database refused it — already settled, or that event already paid another tip |
| notification throws | log, leave it CONFIRMED | the money moved and the row is correct; Telegram being down cannot un-receive a tip |

Notifications go to both sides, and to one person only once when a creator tests with `/tip`.

---

## Group tipping ✅ DONE

`/tip` (or `!tip`) in a group. Sent as a **reply**, it tips that person; on its own, it tips
the group's own wallet. The amount menu and payment button land in the group — one tap, nobody
leaves the conversation.

Because a private chat's `chatId` **is** the user's id, `creators` already supported per-person
lookup — reply-tipping needed no schema change.

### Telegram privacy mode gates `!tip`

`getMe` reports `can_read_all_group_messages: false`, the BotFather default. A bot in a group
only receives messages starting with `/`, mentioning it, or replying to it — **`!tip` never
arrives** until privacy mode is turned off in BotFather. `/tip` works either way, so both
prefixes are accepted and the choice is a deployment decision, not a code one.

### A group is not a private chat

The DM rule "any message that parses as an address is a registration" was actively dangerous
here: it would answer *every line anyone typed* with an address complaint, and the bot would be
removed within minutes. In a group the bot now stays silent on ordinary chatter, on unknown
`/commands` (they belong to other bots), and on anything addressed to `@another_bot`.

### Setting a group's wallet is admin-only

Otherwise any member could run `/setup <their own address>` and redirect the group's earnings.
The check is a `getChatMember` call, resolved lazily so it costs a round trip only for `/setup`,
and it **fails closed** — a failed lookup is treated as "not an admin".

### The tipper is the person, never the chat

On a button tap in a group the callback's `from.id` is the tipper; `chatId` is the room. Using
the chat id would credit the tip to the group and send the thank-you to everyone.

### Accepted: public nonces

In a group the nonce is posted in the open, so it is unguessable but not secret. A stranger
could attach a live nonce to their own transfer and have it credited as their tip. The creator
is paid either way — the exposure is mis-attribution, not theft — and it buys the one-tap flow.

---

## One bot, one person, one wallet ✅ DONE

The final shape, and a large simplification. `@tipping_bot_for_user123` is deployed to collect
for user123 and nobody else. The wallet is baked in at deploy time:

```yaml
name: "@user123"
wallet: "EQCD39VS..."
# ownerChatId: 123456789   # optional private notification
```

### What this deleted

Personalising the bot removed a whole layer rather than adding one:

- the `creators` table, and `upsertCreator` / `findCreator` with it
- the multi-wallet `wallets:` list and its per-chat mapping
- `/setup` in every form, and the `allowSelfSetup` flag
- the group-admin `getChatMember` check — nothing in chat can change a wallet, so there is no
  privileged action left to guard
- reply-targeting, `/chatid`, and the `?start=<creatorId>` deep-link payload

Net: **137 tests down to 110**, and the address now comes from one place instead of being looked
up per message.

### Confirmations land where the tip was raised

`tips.creator_chat_id` became `origin_chat_id` — the chat the tip was asked for in. A tip raised
in a group is announced *in that group*, where the owner and everyone else sees it, which is the
social proof that produces the next tip.

This also fixed a real gap in the previous design: Telegram refuses to message anyone who has
never opened a chat with the bot, so confirmations sent only as private messages silently failed
for recipients who had never pressed Start. The money arrived and nobody was told.

Notification targets are de-duplicated, so a tip raised in the owner's own chat produces one
message rather than three.

### The address is still recorded per tip

Even with a single wallet, `tips.raw_address` stays. Changing the configured wallet must not
redirect an invoice that is already in flight, and the poller groups pending tips by address, so
a wallet change is handled with no special cases.

### Startup refuses a bad wallet

A typo'd TON address is a valid-looking string that silently swallows every tip sent to it, so
the deployment aborts rather than running. A missing config file is refused with instructions
rather than a stack trace.

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
./gradlew test                 # 110 tests, all green
./gradlew runBot               # the Telegram bot
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
| `TIPBOT_WALLETS` | `tipbot.yaml` | the owner's config file |
| `TIP_POLL_SEC` | `10` | how often the poller checks. Raise it, or set `TONAPI_KEY`, before pointing many creators at one deployment |

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
