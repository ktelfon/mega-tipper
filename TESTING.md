# Test plan

Work through these in order. Each one is numbered — say **"T7 done"** or **"T7 failed, it said X"**
and I'll read the log and database and tell you what actually happened.

Phases are ordered by cost: nothing at all, then a few cents of gas, then another person. Stop
at any point; each phase is useful on its own.

**Who does what**

- 🤖 **I run it** — automated, no Telegram, no wallet, no money.
- 👤 **You run it** — needs a real Telegram client, a real wallet, or a second person. These
  cannot be automated, which is exactly why they are worth doing by hand.

---

## Phase 0 — Automated (🤖 ask me)

Run these after any change. They take about ten seconds.

- [ ] **T1** 🤖 `./gradlew test` — the full suite, 133 tests.
- [ ] **T2** 🤖 `./gradlew e2e` — the whole bot driven with real Telegram update JSON: group
      `/tip` → tap → invoice → payment → public confirmation, plus channel posts, deep links,
      and an unpaid invoice expiring.

If these are green, every layer we own is working. What follows tests the parts we do not own:
Telegram's wire, the wallet apps, and the chain.

---

## Phase 1 — Free (👤 you, no money moves)

Nothing here can spend anything. Do them all — they are fast.

- [ ] **T3** 👤 DM the bot `/tip banana` → refused, reason mentions the amount.
- [ ] **T4** 👤 DM `/tip 0.0001` → refused as too small (the fee would exceed the tip).
- [ ] **T5** 👤 DM `/tip 100000` → refused as over the limit (catches a fat-fingered digit).
- [ ] **T6** 👤 DM `/wallet` → shows `UQAV_trg…`, so a tipper can verify where money goes.
- [ ] **T7** 👤 Add the bot to a test **group**, send `/tip` → amount buttons appear.
- [ ] **T8** 👤 In that group, chat normally: "hello", "0.5", "what do you think" → **silence.**
      A bot that answers ordinary chatter gets removed from groups within minutes.
- [ ] **T9** 👤 In that group, send `/roll 2d6` → silence (it belongs to some other bot).

**After T3–T9:** nothing should have been created. Ask me and I'll confirm the database is still
empty of new invoices.

---

## Phase 2 — Wallet links (👤 you, still free)

**Only Tonkeeper is proven.** The other three buttons have never been opened. You can check each
one for free: tap it, look at the screen, then **cancel before confirming.**

For each, verify all three fields are pre-filled:

- **Address** matches your wallet
- **Amount** matches what you picked
- **Comment** reads `tip_` + 16 hex characters ← *the one that matters*

If the comment is missing or empty, that wallet's link is wrong and I need to fix or remove it.
A payment without the comment still reaches you, but the tip never confirms.

- [ ] **T10** 👤 DM `/tip 0.01`, tap **Tonhub** → check three fields → cancel.
- [ ] **T11** 👤 Same invoice, tap **MyTonWallet** → check three fields → cancel.
- [ ] **T12** 👤 Same invoice, tap **Telegram Wallet** → it only *opens* Wallet (it cannot be
      deep-linked). Confirm the three copy-paste lines are readable in the bot's message above.
- [ ] **T13** 👤 Copy the `ton://transfer/…` link from the message into any other TON wallet →
      check the same three fields.

---

## Phase 3 — Money (👤 you, ~0.005 TON gas each)

Every payment lands in **your own wallet**, so nothing is spent but the network fee. These test
the failure paths, which matter more than the happy path — the happy path already works.

- [ ] **T14** 👤 Request `/tip 0.01`, then **pay a different amount** (say 0.02).
      → Should **never** confirm. The amount is compared exactly, with no tolerance.
- [ ] **T15** 👤 Request `/tip 0.01`, **delete or change the comment** before sending.
      → Should **never** confirm. Money arrives; the tip does not.
- [ ] **T16** 👤 Request `/tip 0.01`, **wait 16 minutes**, then pay.
      → Money arrives, **no announcement**. The window is what stops an old transfer being
      replayed as a new tip.
- [ ] **T17** 👤 Request **two** invoices, pay only one.
      → Exactly one confirms; the other expires quietly.
- [ ] **T18** 👤 Request one invoice and **pay it twice**.
      → One confirmation only. The `event_id` unique index refuses the second.
- [ ] **T19** 👤 Request an invoice, tell me to **restart the bot**, *then* pay.
      → Still confirms. Proves the invoice survives a redeploy rather than living in memory.

**T14, T15 and T18 are the important ones.** They are the cases where money moves but the bot
must *not* say a tip arrived.

---

## Phase 4 — A second person (👤 you + someone else)

The genuinely untested area: every payment so far has been you paying yourself.

- [ ] **T20** 👤 Tip from **TON Space** inside `@wallet` — a different address from your Tonkeeper
      wallet. → Confirms, and `sender_address` in the database is genuinely different from yours.
- [ ] **T21** 👤 Have **someone else** tip you in a group.
      → The confirmation is posted **publicly in the group**, and the tipper gets their own
      thank-you privately. This is the social-proof behaviour and has never run for real.
- [ ] **T22** 👤 Have someone tip who has **never opened a chat with the bot**.
      → The group still sees the confirmation. Telegram will not let a bot message a stranger,
      so this checks the announcement does not depend on that.

---

## Phase 5 — Optional

- [ ] **T23** 👤 Turn privacy mode off in BotFather (`/mybots` → Bot Settings → Group Privacy →
      Turn off), then send `!tip` in a group → works like `/tip`.
      **Tradeoff:** with privacy off the bot receives *every* message in every group it is in.
      Only worth it if `!tip` matters to you.
- [ ] **T24** 👤 Post `/tip` in a **channel** → a tip card appears with a `Tip @Mdefman` button.
      *(Already done once — worth repeating after any change to channel handling.)*

---

## How I verify a result

Tell me the number and I will check, for that specific test:

- the bot's log — what update arrived, what it decided, what it confirmed or refused
- the `tips` table — status, event id, sender address, timings
- TonAPI directly, if we need to see what the chain actually reported

I can tell the difference between "the payment never arrived", "it arrived and was correctly
refused", and "it arrived and was wrongly ignored" — which is not obvious from the chat alone.

---

## Known gaps no test here closes

- **Nothing can automate the wallet tap.** T10–T22 exist because that step is irreducibly manual.
- **Acting as a Telegram user** would need an MTProto client (Telethon or similar) signed in to a
  real account with `api_id`, `api_hash` and a phone-code login. Bots cannot message bots, so
  there is no way around that.
- **Load and abuse.** Nothing here spams `/tip` to fill the pending table — that is inbound flood
  control, still open in step 6 of [PLAN.md](PLAN.md).
