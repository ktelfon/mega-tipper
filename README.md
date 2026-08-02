# TON Tip Bot

A Telegram bot that lets people tip in TON. Someone types `/tip` in a group, taps an amount,
and pays from their own wallet straight into the recipient's.

**Nothing is ever held.** The bot has no wallet, no keys, and no balance. It only watches the
blockchain and confirms that a payment arrived. If the bot disappears mid-payment, the money is
already where it was going.

You run it *for* people. A group owner asks for a tip bot; you add a line to a config file and
deploy. Nobody in the group is asked to paste an address, and nobody in the group can change one.

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

### 3. Find the group's chat id

Add your bot to the group, then send `/chatid` in it. The bot replies with a number like
`-1001234567890`. That is what identifies the group.

### 4. Put the wallet in the config file

```bash
cp tipbot.yaml.example tipbot.yaml
```

```yaml
allowSelfSetup: false

wallets:
  - chatId: -1001234567890
    label: "Bob's Crypto Chat"
    address: "EQCD39VS5jcptHL8vMjEXrzGaRcCVYto7HUn4bpAOg8xqB2N"
```

Any address spelling works — `EQ…`, `UQ…`, or raw `0:…`. They are all the same wallet, and the
bot converts to the one canonical form internally.

**If an address has a typo, the bot refuses to start** and tells you which entry is wrong. That
is deliberate: a typo'd TON address is a valid-looking string, and tips sent to one are gone
forever. Better a deployment that won't boot than a bot that quietly swallows people's money.

### 5. Run it

```bash
./gradlew runBot
```

You should see:

```
Collecting tips for 1 chat(s):
  -1001234567890  Bob's Crypto Chat  -> 0:83dfd552e63729b472fcbcc8c45ebcc6691702558b68ec7527e1ba403a0f31a8
@your_bot is running on mainnet. Ctrl+C to stop.
Poller started, checking every 10s
```

That's it. The group can now take tips.

### 6. Optional: allow `!tip` as well as `/tip`

By default Telegram only delivers messages starting with `/` to a bot in a group. If you want
`!tip` to work, turn off privacy mode: BotFather → `/mybots` → your bot → Bot Settings →
Group Privacy → **Turn off**.

Be deliberate about this — with privacy off, your bot receives *every message* in every group it
is in. The bot logs only the command word, never message text, but it is still a real change in
what you are handling.

---

## Using it

In a group:

| What you type | What happens |
|---|---|
| `/tip` | amount buttons for the group's wallet |
| `/tip` *as a reply to someone* | amount buttons that pay **that person** |
| `/tip 5` | straight to a 5 TON payment button |
| `/chatid` | the id of this chat, for the config file |

Tapping an amount posts a payment button. The tipper taps it, their wallet opens with the
address, amount and a reference comment already filled in, and they confirm. Within about ten
seconds the bot announces that the tip arrived.

To tip a **person** by replying to them, that person needs their own entry in `tipbot.yaml`
(their user id is positive, and is the same number as their private chat with the bot — they can
get it by sending `/chatid` to the bot directly).

### Testing without a second account

Send the bot a direct message:

```
/tip 0.01
```

That bills *your own* registered wallet, so the money goes from you to you and the only real
cost is about 0.005 TON of network fees. It exercises the entire path — invoice, link, wallet,
chain, confirmation.

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
| `TIPBOT_WALLETS` | `tipbot.yaml` | path to the wallet file |

`tipbot.yaml`:

| Key | Purpose |
|---|---|
| `allowSelfSetup` | let `/setup` change wallets from chat. Keep `false` for operator-run deployments |
| `wallets[].chatId` | group (negative) or user (positive) id |
| `wallets[].label` | your own name for it; logs and error messages only |
| `wallets[].address` | where the tips go |

The wallet file is re-applied on every boot, so **edit and restart** is the whole workflow.

### Hosting note

The default SQLite file is fine on a VPS with a real disk. Many cloud hosts have an ephemeral
filesystem that is wiped on every redeploy — which would take the wallet mappings *and* the
double-payout guard with it. On those, point `TIPBOT_JDBC_URL` at Postgres; nothing else changes.

---

## Development

```bash
./gradlew test      # 137 tests
./gradlew runBot    # start the bot
```

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
