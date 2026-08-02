# Deployment

Getting the bot off a laptop. Long polling means **no public URL, no inbound port, no TLS
certificate** — the same image runs behind any NAT, which removes most of what usually makes
deployment annoying.

Measured, not estimated: **156 MiB** per bot, image **247 MB**.

---

## What to run it on

You need one bot process per customer, so the question is how cheaply you can run many small
JVMs, not how big one server needs to be.

**A small VPS with Docker.** Hetzner CX22 or equivalent — roughly €4/month, 4 GB RAM, a real
disk. At 156 MiB each that is comfortably **20+ bots on one box**, and adding a customer costs
nothing extra until you run out of memory.

Avoid platforms with an ephemeral filesystem (many free tiers) unless you also move to Postgres.
A wiped disk takes the in-flight invoices *and the double-payout guard* with it — the guard is a
unique index in that database, so losing it means a replayed event could pay out twice.

---

## First deploy

On a fresh Ubuntu box:

```bash
# 1. Docker
curl -fsSL https://get.docker.com | sh

# 2. This repo
git clone https://github.com/ktelfon/mega-tipper.git && cd mega-tipper

# 3. Secrets - never committed, never baked into the image
cat > .env <<'ENV'
MDEFMAN_BOT_TOKEN=<token from @BotFather>
# TONAPI_KEY=<optional, raises the rate limit for every bot on this host>
ENV

# 4. The customer's wallet
cp tipbot.yaml.example customers/mdefman.yaml
$EDITOR customers/mdefman.yaml     # set name and wallet

# 5. Go
docker compose up -d --build
docker compose logs -f mdefman
```

Expect:

```
Collecting tips for @Mdefman -> 0:15fedae08ddc…
@mega_tipper_bot is running on mainnet.
Poller started, checking every 10s
```

If the wallet address has a typo, **it refuses to start and names the problem** rather than
running and silently swallowing tips.

---

## Adding a customer

1. `@BotFather` → `/newbot` → name it after them, e.g. `tipping_bot_for_dana`
2. Add `DANA_BOT_TOKEN=…` to `.env`
3. `cp tipbot.yaml.example customers/dana.yaml` and fill in their wallet
4. Copy the `mdefman:` block in `docker-compose.yml`, replace every `mdefman` with `dana`
5. `docker compose up -d`

Nothing is shared: separate token, separate wallet file, separate volume. One customer's bot
cannot see another's invoices, and `docker compose rm -sf dana` removes that customer and
nothing else.

---

## Updating

```bash
git pull
docker compose up -d --build
```

Each bot restarts in a few seconds. **Pending invoices survive** — they live in the volume, not
in memory, so a tip requested before the restart still confirms after it. That is tested (T19 in
[TESTING.md](TESTING.md)).

---

## Day to day

```bash
docker compose ps                      # what is running
docker compose logs -f mdefman         # follow one bot
docker compose logs --since 1h mdefman # what happened recently
docker compose restart mdefman         # pick up an edited customers/*.yaml
docker compose down                    # stop everything, keep the volumes
```

Reading a customer's database directly:

```bash
docker run --rm -v mega-tipper_mdefman-data:/data alpine \
  sh -c 'apk add -q sqlite && sqlite3 /data/tipbot.db "select nonce,status,amount_nano from tips"'
```

---

## Backups

The only irreplaceable state is each volume's `tipbot.db`. Wallet files are in git-ignored
config you can rewrite; tokens can be reissued by BotFather.

```bash
docker run --rm -v mega-tipper_mdefman-data:/data -v "$PWD:/backup" alpine \
  tar czf /backup/mdefman-$(date +%F).tar.gz -C /data .
```

Worth a nightly cron. Losing it does not lose anyone's money — the money is on-chain in the
customer's own wallet, and this bot never holds any — but it does lose the record of which
payments were already credited.

---

## Moving to Postgres

Only needed on a host without a persistent disk. Add a `db` service, then point the bot at it:

```yaml
environment:
  TIPBOT_JDBC_URL: jdbc:postgresql://db:5432/tipbot
  TIPBOT_JDBC_USER: tipbot
  TIPBOT_JDBC_PASSWORD: ${PG_PASSWORD}
```

No code changes. The schema is deliberately portable and the same contract tests run against
both engines — see [PLAN.md](PLAN.md), step 2.

---

## Security notes

- The container runs as an **unprivileged user**. Nothing it does needs root.
- **Tokens are passed at run time**, never baked into an image layer. `.dockerignore` excludes
  `.env` and every real `customers/*.yaml` so they cannot end up in a layer by accident.
- **No inbound port is opened.** Long polling is outbound-only, so there is no listening socket
  to attack and nothing to firewall.
- The bot **cannot move anyone's money**. It holds no keys and has no wallet — the worst a stolen
  token achieves is impersonating the bot in chat, which is fixed with `/revoke` in BotFather.
- Logs record the **command word only**, never message text.
