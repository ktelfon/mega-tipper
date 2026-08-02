# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A non-custodial Telegram tipping bot for TON. Money moves wallet-to-wallet; this process never
holds funds — it only issues invoices, watches the chain, and notifies. Kotlin/JVM, Gradle,
everything under `dev.tipbot.spike` (the package name is a leftover from the step-0 spike).

`PLAN.md` is the design record: it explains, step by step, *why* each decision was made and which
real failure each defence exists to stop. Read the relevant section before changing matching,
storage, or address handling — most of the surprising code there is deliberate.

## Commands

```bash
./gradlew test                                  # 123 tests
./gradlew test --tests "*.TipMatcherTest"       # one class
./gradlew test --tests "*.TipMatcherTest.rejects replayed historical transfer"   # one test
./gradlew runBot                                # the Telegram bot (needs .env)
./gradlew run --args="<address> <comment> <amountTon> [timeoutSec] [pollSec]"    # chain-watching spike
```

The Postgres contract tests skip unless a server is reachable, so a green build does **not** mean
they ran. To include them:

```bash
docker run -d --rm --name tipbot-pg -e POSTGRES_PASSWORD=test -e POSTGRES_DB=tipbot \
  -p 55432:5432 postgres:16-alpine
./gradlew cleanTest test
```

Needs a **JDK 21**, not a JRE. Gradle auto-detects `~/.jdks/`, SDKMAN, and system installs; a JRE
surfaces as the misleading *"Cannot find a Java installation matching languageVersion=21"*.

## Configuration: two files, different jobs

- `.env` (gitignored, see `.env.example`) — secrets and runtime knobs, read by `Config.load()`.
  Real environment variables win over the file.
- `tipbot.yaml` (gitignored, see `tipbot.yaml.example`) — the operator's wallet directory: which
  chat ids this deployment collects tips for, and where the money goes.

The YAML file is **authoritative and re-applied to the database on every boot**
(`WalletDirectory.apply`), so editing it and restarting is the whole configuration workflow. A bad
address aborts startup rather than letting the bot run and silently swallow tips. `allowSelfSetup`
in that file decides whether `/setup` can change a wallet from inside Telegram; default off,
because the deployment model is operator-run.

`TIP_LOOKBACK_SEC` widens the invoice window and is **testing only** — a narrow window is what
stops an old transfer with the same comment being replayed as a new tip.

## Architecture

Two independent drivers share one store:

- **Update loop** — `TipBot` (Telegram plumbing) → `CommandHandler` (all decisions) → `TipStore`.
- **Poller** — `TipPoller` on a daemon thread → `TonApiClient` → `TipMatcher` → `TipStore` →
  notify. Confirmation is driven by the blockchain, not by anyone sending a message, so it cannot
  live in the update loop.

Both are wired together in `main()` in `TipBot.kt`.

### The purity boundary is the testing strategy

`CommandHandler`, `TipMatcher`, `TipAmount`, `TipLink`, `AddressNormalizer` and `WalletDirectory`
know nothing about Telegram types or the network; `TipPoller.pollOnce()` is a single pass taking
canned JSON and returning what it confirmed. That is why 123 tests run with no token, no network
and no clock. Keep new logic on that side of the line — `TipBot` should stay thin plumbing that
translates Telegram objects to and from `CommandHandler.Incoming` / `Reply`.

### Invariants worth knowing before editing

- **Addresses are stored raw (`0:83df…`) and only ever compared raw**, because that is what TonAPI
  reports. Storing a user-friendly spelling makes real payments silently fail to match. Payment
  links render the **non-bounceable** (`UQ`/`0Q`) form — a bounceable transfer to an undeployed
  wallet bounces back, which is exactly the new creator collecting their first tip.
- **`ton4j`'s `Address.of()` throws `java.lang.Error`, not `Exception`,** on bad input.
  `AddressNormalizer` catches `Throwable` and rethrows only `VirtualMachineError`/`LinkageError`.
- **Amounts are `BigDecimal`/nanoTON longs, never `Double`.** `TipMatcher` compares exactly with no
  tolerance, so one bit of float error yields an invoice no correct payment can satisfy.
- **The double-payout guard is `UNIQUE INDEX ON tips(event_id)`,** not application logic.
  `store.confirm()` returning false means "do not notify, do not deliver" — never work around it.
- **The DDL runs unchanged on SQLite and Postgres**: BIGINT everywhere, epoch seconds not
  TIMESTAMP, no AUTOINCREMENT/SERIAL, `ON CONFLICT … DO UPDATE`. Switching engines is a
  connection-string change; keep it that way, and run the contract tests against both.
- **`CommandHandler` is stateless** — no "awaiting input" flag anywhere. The creator id and amount
  travel in the deep link and the 64-byte callback payload, so a restart strands nothing.
- **A group is not a private chat.** In a group the bot stays silent on ordinary text, on unknown
  `/commands` (they belong to other bots), and on anything addressed to `@another_bot`; `/setup` is
  admin-only and fails closed. Breaking this makes the bot a spammer that gets removed in minutes.
- **In a group, the tipper is `from.id`, never `chatId`** — the chat id would credit the tip to the
  room and thank everyone.
- Both `/tip` and `!tip` are accepted: Telegram privacy mode (BotFather default) means `!tip` never
  reaches the bot until it is turned off, so the prefix is a deployment choice, not a code one.

### Poller failure rules (each has a test)

429 → end the pass immediately, the next sleep is the back-off. One creator's lookup failing → log
and carry on with the others. Any exception in a cycle → log and keep the loop alive. Notification
throwing → log and leave the tip CONFIRMED; the money moved and Telegram being down cannot
un-receive a tip.

## Testing notes

`TipMatcherTest` names each test after the attack it stops — keep that convention when adding
checks. `TipStoreContractTest` is the shared contract; `SqliteTipStoreTest` and
`PostgresTipStoreTest` are the two engines. This bot is exercised against **live mainnet** with
tiny real amounts, not testnet.
