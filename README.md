# TON Tip Bot

A personalised Telegram tip bot. **One bot, one person, one wallet.**

`@tipping_bot_for_user123` exists to collect tips for user123 and nobody else. Someone types
`/tip` wherever the bot is, taps an amount, and pays from their own wallet straight into
user123's. The wallet is baked in at deploy time, so there is nothing to set up in chat and no
message anyone can send that points the money somewhere else.

**Nothing is ever held.** The bot has no wallet, no keys, and no balance. It only watches the
blockchain and confirms that a payment arrived. If the bot disappears mid-payment, the money is
already where it was going.

You run it *for* people. Someone asks for a tip bot; you make them one with BotFather, put their
wallet in a config file, and deploy.

---

## What it's actually for

If you run a Telegram community — a trading group, a study group, a fan channel, a support
server — some of your members would happily pay you something. Almost none of them will go find
your Patreon, make an account, enter a card, and set up a subscription. The gap between
*wanting to support you* and *having supported you* is where the money dies.

This closes that gap to two taps, inside the conversation they were already having.

### For the person collecting

- **You keep everything.** Tips go from the tipper's wallet directly into yours. There is no
  platform cut, no processing fee, and no payout schedule — because there is no platform sitting
  in the middle to take one.
- **Nobody can freeze or reverse it.** You are not an account on someone's service. The money is
  in your wallet the moment it lands, and no one — including whoever runs the bot — can claw it
  back, hold it pending review, or close you down.
- **It works anywhere.** No bank account, no business registration, no country restrictions, no
  minimum payout threshold. If you can install a wallet app, you can get paid.
- **You never handle setup.** Whoever runs the bot adds you. You don't paste your address into a
  chat, so you can't fat-finger it and lose tips to a typo.

### For the person tipping

- **No account, no signup, no card.** If you have a TON wallet, you can tip. There is nothing to
  register for and no email to hand over.
- **Two taps.** Pick an amount, confirm in your wallet. Your wallet opens with the address,
  amount and reference already filled in — there is nothing to copy, paste, or get wrong.
- **You never leave the chat.** No redirect to a checkout page on some other site.
- **It arrives in seconds,** and you get told when it did.

### For whoever runs the bot

One bot per customer, named after them. Setting one up is a BotFather token, two lines of YAML
and a deploy. You never touch anyone's money, so you are not a payment processor, you hold no
balances, and there is nothing for you to be liable for or to lose.

### What it looks like in use

Dana runs a trading group. `@tipping_bot_for_dana` sits in it. Dana posts something useful:

```
Dana:  here's the fix — set the timeout before the retry, not after

Sam:   /tip

Bot:   How much would you like to tip @dana?
       [ 0.5 TON ]  [ 1 TON ]  [ 5 TON ]

Sam taps 1 TON

Bot:   Tip of 1 TON to @dana

       Tap the button to open your wallet, or use this link:
       ton://transfer/UQCD39VS...?amount=1000000000&text=tip_9f3a1c7b2d4e6f80
       [ Pay 1 TON ]

Sam taps Pay, confirms in Tonkeeper — about ten seconds pass

Bot:   Tip received: 1 TON. Thank you!
```

That last message lands **in the group**, where Dana and everyone else sees it — which is the
social proof that makes the next person tip. Dana did nothing. Sam signed up for nothing. The
money moved directly between two wallets and the bot only ever watched.

### Being straight about the limits

- **The tipper needs a TON wallet with TON in it.** That is the real barrier, and it is a bigger
  one than a card. This works best where people already hold crypto.
- **Amounts are in TON, and TON moves.** A 1 TON tip is not a fixed amount of money.
- **Each transfer costs about 0.005 TON in network fees,** paid by the tipper. Tips below
  0.01 TON are refused because the fee would swallow them.
- **Each person needs their own bot.** That is the point of the model — the wallet is baked in
  and unchangeable — but it does mean it is not self-serve, and one bot cannot serve two people.
- **A tip that isn't paid within 15 minutes expires.** If someone pays late the money still
  reaches the wallet, but the bot will not announce it. The window is what stops an old payment
  being replayed as a new one.
- **Telegram will not let a bot message someone who has never opened a chat with it.** This is
  why confirmations go to the chat the tip was raised in rather than only to the owner. If you
  set `ownerChatId`, the owner must have pressed Start on their bot at least once.

---

## Setup, step by step

### 1. Get a bot token

Message [@BotFather](https://t.me/BotFather) on Telegram:

- `/newbot`, pick a name and a username
- Copy the token it gives you. **Treat it as a password** — anyone holding it controls the bot.

### 2. Configure secrets

```bash
cp .env.example .env
```

Open `.env` and set:

```
TELEGRAM_BOT_TOKEN=<the token from BotFather>
```

Everything else has a working default. Mainnet, SQLite, 10-second polling.

Name it after whoever it collects for — `@tipping_bot_for_user123` — so nobody has to wonder
where the money is going.

### 3. Put their wallet in the config file

```bash
cp tipbot.yaml.example tipbot.yaml
```

```yaml
name: "@user123"
wallet: "EQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqB2N"

# Optional: also tell them privately whenever a tip lands.
# ownerChatId: 123456789
```

Any address spelling works — `EQ…`, `UQ…`, or raw `0:…`. They are all the same wallet, and the
bot converts to the one canonical form internally.

**If the address has a typo, the bot refuses to start.** That is deliberate: a typo'd TON
address is a valid-looking string, and tips sent to one are gone forever. Better a deployment
that won't boot than a bot that quietly swallows people's money.

### 4. Run it

```bash
./gradlew runBot
```

You should see:

```
Collecting tips for @user123 -> 0:83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8
@tipping_bot_for_user123 is running on mainnet. Ctrl+C to stop.
Poller started, checking every 10s
```

That's it. Add the bot to any group and it can take tips there.

### 5. Optional: allow `!tip` as well as `/tip`

By default Telegram only delivers messages starting with `/` to a bot in a group. If you want
`!tip` to work, turn off privacy mode: BotFather → `/mybots` → your bot → Bot Settings →
Group Privacy → **Turn off**.

Be deliberate about this — with privacy off, your bot receives *every message* in every group it
is in. The bot logs only the command word, never message text, but it is still a real change in
what you are handling.

---

## Using it

Anywhere the bot is — a group, or its own private chat:

| What you type | What happens |
|---|---|
| `/tip` | asks how much, with amount buttons |
| `/tip 5` | straight to a 5 TON payment button |
| `/wallet` | shows the address, so a tipper can check where the money goes |
| `/link` | the bot's own link, for the owner to share |

Tapping an amount posts a payment card with **one button per wallet** — Tonkeeper, Tonhub,
MyTonWallet and Telegram Wallet — plus a plain `ton://transfer` link for anything else, and the
address/amount/comment as copy-paste lines for paying by hand. The tipper
taps the wallet they already have, it opens with the address, amount and a reference comment
already filled in, and they confirm. Within about ten
seconds the bot announces that the tip arrived — **in the same chat the tip was asked for**, so
a tip raised in a group is confirmed publicly in that group.

### Testing it

Send the bot a direct message:

```
/tip 0.01
```

Then pay it from the owner's own wallet: the money goes from that wallet back to itself, so the
only real cost is about 0.005 TON of network fees, and it exercises the entire path — invoice,
link, wallet, chain, confirmation.

---

## How it decides a tip was really paid

A comment on a TON transfer is public plaintext that anyone can attach to anything. Live mainnet
data shows both duplicate comments and zero-value dusting scams, so a payment is only credited
when **all** of these hold:

- it landed on the right address
- the transaction status is `ok`
- the amount matches **exactly**, with no tolerance
- the comment matches exactly, character for character
- it happened inside the invoice's 15-minute window
- the event is not flagged as spam
- the trace has settled
- that blockchain event has never paid out any other tip

The last one is enforced by a unique database index rather than by application logic, so two
overlapping polls cannot both credit the same payment.

---

## Configuration

`.env`:

| Var | Default | Purpose |
|---|---|---|
| `TELEGRAM_BOT_TOKEN` | — | **required**, from BotFather |
| `TONAPI_BASE_URL` | `https://tonapi.io` | `https://testnet.tonapi.io` for testnet |
| `TONAPI_KEY` | — | optional, raises the rate limit ([tonconsole.com](https://tonconsole.com)) |
| `TIPBOT_JDBC_URL` | `jdbc:sqlite:tipbot.db` | use Postgres on a host with no persistent disk |
| `TIP_POLL_SEC` | `10` | how often to check for payments |
| `TIPBOT_WALLETS` | `tipbot.yaml` | path to the owner's config file |

`tipbot.yaml`:

| Key | Purpose |
|---|---|
| `name` | shown wherever a reply names who is being tipped |
| `wallet` | **required** — where every tip goes |
| `ownerChatId` | optional; also tell the owner privately when a tip lands |

To change the wallet, **edit and restart**. Invoices already in flight keep the address they
were issued against, so an in-progress payment is never redirected.

### Hosting note

The default SQLite file is fine on a VPS with a real disk. Many cloud hosts have an ephemeral
filesystem that is wiped on every redeploy — which would take the wallet mappings *and* the
double-payout guard with it. On those, point `TIPBOT_JDBC_URL` at Postgres; nothing else changes.

---

## Development

```bash
./gradlew test      # 133 tests
./gradlew e2e       # end-to-end only: real Telegram JSON in, sent messages out
./gradlew runBot    # start the bot
```

`e2e` drives the real update router with the JSON Telegram actually sends — a group `/tip`, a
button tap, a channel post, a callback — captures every message the bot tries to send, and then
runs the poller against a canned TonAPI payment to check the confirmation lands in the right
chat. No network, no token, no wallet.

It exists because the unit tests all called `CommandHandler` directly, so nothing exercised
`TipBot.consume()` — which is precisely where channel posts were being dropped while 116 tests
stayed green.

To include the Postgres storage tests (they skip when no server is reachable):

```bash
docker run -d --rm --name tipbot-pg -e POSTGRES_PASSWORD=test -e POSTGRES_DB=tipbot \
  -p 55432:5432 postgres:16-alpine
./gradlew cleanTest test
```

You need a **JDK 21**, not just a JRE. Gradle auto-detects one in `~/.jdks`, via SDKMAN, or
installed system-wide. A JRE surfaces as a confusing *"Cannot find a Java installation matching
languageVersion=21"* rather than an obvious "no compiler".

See [PLAN.md](PLAN.md) for how it is built and why each decision went the way it did.
