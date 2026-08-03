# Deployment

Getting the bot off a laptop. Long polling means **no public URL, no inbound port, no TLS
certificate** — the same image runs behind any NAT, which removes most of what usually makes
deployment annoying.

Measured, not estimated: **78 MiB** per bot, image **247 MB**.

---

## What to run it on

You need one bot process per customer, so the question is how cheaply you can run many small
JVMs, not how big one server needs to be.

**A small VPS with Docker.** Hetzner CX22 or equivalent — roughly €4/month, 4 GB RAM, a real
disk. At 78 MiB each that is **40-odd bots on one box** — about 9 cents per customer per month.

A **1 GB droplet runs this fine** (measured: 79 MiB in use), but 1 GB is not enough to *compile*
Kotlin — add swap before the first build or Docker will be OOM-killed mid-build:

```bash
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile
echo "/swapfile none swap sw 0 0" >> /etc/fstab
```

Only if you build **on** the box. Once CI is set up (see *Continuous deployment* below) the
droplet pulls a finished image and never compiles anything, so this stops being necessary.

### Why 78 MB and not less

That is a JVM floor, not this application's data — the bot itself holds almost nothing. A Go or
Rust rewrite would land around 10–15 MB. It is not worth it: the saving is ~7 cents per customer
per month, against throwing away a proven matcher, 133 tests and a real payment that has been
through the whole path.

If density ever does matter, the fix is not a rewrite. `TelegramBotsLongPollingApplication`
registers **many bots in one process**, so N customers could share one JVM — one 78 MB floor
instead of N. Each customer keeps their own bot identity and wallet; only the process is shared.

**Memory is not the first constraint anyway.** TonAPI's anonymous rate limit binds sooner, and
`TIP_POLL_SEC` or a `TONAPI_KEY` is the lever for that — not RAM. Note an idle bot makes no API
calls at all, since `pollOnce()` returns before requesting anything when nothing is pending.

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
chmod 600 .env

# 4. The customer's wallet
cp tipbot.yaml.example customers/mdefman.yaml
$EDITOR customers/mdefman.yaml     # set name and wallet

# 5. Go - pull the published image rather than building it here
docker compose pull && docker compose up -d
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

## File permissions — the one that will bite you

```bash
chmod 600 .env                     # a real secret: the bot token
chmod 644 customers/*.yaml         # bind-mounted, and read by an unprivileged container user
```

The container deliberately runs as a non-root user, so a `600` wallet file owned by root on the
host is **unreadable inside the container** and the bot crash-loops with
`tipbot.yaml is not valid YAML: Permission denied`. That message names the wrong cause; the file
is fine, the permissions are not.

`644` is right here: a wallet file holds a display name and an address that is public on-chain
anyway. The token is the actual secret, and it is passed as an environment variable rather than
mounted, so it stays `600` and never appears in the filesystem the container can see.

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

## Continuous deployment

Three workflows in `.github/workflows/`, split by how much damage a bad commit can do:

| Push touching | What happens |
|---|---|
| `web/**` | Site deploys itself. Static files on a bind mount — nothing restarts. |
| `src/**`, `Dockerfile`, … | Tests run, an image is built and published. **The droplet is not touched.** |
| anything, on a branch or PR | Tests run. |

Releasing a bot is a button: **Actions → deploy bot → Run workflow**. Restarting a bot can
interrupt someone mid-invoice, so it is a decision, not a side effect of a commit.

**The droplet no longer builds anything.** CI publishes to `ghcr.io/ktelfon/mega-tipper` and the
box pulls a finished image, which is what removes the swap-file problem above — there is no JDK
and no Gradle on the server at all.

### Rolling back

Run **deploy bot** with an older commit sha in the *tag* box. It skips the build and the tests
and repoints the droplet at an image that already exists, so it takes about as long as a `docker
pull`. Every commit that ever deployed is still tagged in the registry.

The deployed tag is written to `TIPBOT_IMAGE_TAG` in the droplet's `.env`, so a `docker compose
up` typed by hand later brings back the same image rather than drifting to `:latest`.

### One-time setup

Under **Settings → Secrets and variables → Actions**:

| Secret | What it is |
|---|---|
| `DROPLET_HOST` | the droplet's IP or hostname |
| `DROPLET_USER` | the SSH user (`root`, or a deploy user) |
| `DROPLET_SSH_KEY` | the **private** key, whole file including the BEGIN/END lines |
| `DROPLET_SSH_KNOWN_HOSTS` | output of `ssh-keyscan <droplet-ip>` |

Optionally a **variable** (not a secret) `DROPLET_PATH` if the clone is not at `~/mega-tipper`.
Give it a path relative to the login home directory or an absolute one — **not** `~/…`, which
arrives quoted and never expands.

Generate a key that exists only for this, so it can be revoked without touching your own:

```bash
ssh-keygen -t ed25519 -f ~/.ssh/tipbot_deploy -C "github-actions" -N ""
ssh-copy-id -i ~/.ssh/tipbot_deploy.pub <user>@<droplet-ip>
ssh-keyscan <droplet-ip>                      # -> DROPLET_SSH_KNOWN_HOSTS
cat ~/.ssh/tipbot_deploy                      # -> DROPLET_SSH_KEY
```

`known_hosts` is pinned rather than `StrictHostKeyChecking=no` on purpose: without it the
workflow would hand a deploy key to whatever answered on that address.

Finally, make the package readable so the droplet can pull without logging in — on the repo's
**Packages** page, *Package settings → Change visibility → Public*. The image holds no secrets
(tokens are passed at run time and `.dockerignore` keeps `.env` out of every layer). If you would
rather keep it private, run `docker login ghcr.io` once on the droplet with a read-only PAT.

### The Postgres tests actually run in CI

`PostgresTipStoreTest` skips itself when no server answers, so a green local build routinely
means that half never ran. The workflow starts a real Postgres **and then asserts the tests
were not skipped** — a silent skip fails the build rather than passing it.

---

## Updating by hand

Still works, and is the right move when CI is not involved:

```bash
git pull
docker compose pull && docker compose up -d
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

## The web page

Everything the public site needs lives under **`web/`** and nothing in there belongs to the bot:

```
web/
  Caddyfile        the server config
  site/            what gets served - index.html, styles.css, main.js, fonts/
```

The bot has no idea it exists: separate container, separate image, no shared volume, and
`.dockerignore` keeps `web/` out of the bot's build context entirely. `web/site/` is served by a
`caddy` container on port 80. Edit a file and it is live immediately — it is a read-only bind
mount, so there is no rebuild and no restart.

Fonts are **self-hosted** in `web/site/fonts/`. That is deliberate: a webfont CDN would make every
visitor's browser announce itself to a third party, and a blocked request would silently fall back
to a system font and quietly wreck the layout.

```bash
docker compose up -d web           # start it
curl http://<droplet-ip>/          # should be 200
```

If the droplet has `ufw` enabled, `ufw allow 80,443/tcp`. A DigitalOcean **cloud firewall** is a
separate thing configured in their control panel, and blocks the port even when `ufw` allows it.

### Adding a domain later

A certificate authority will not issue a certificate for a bare IP address, so the site is
plain HTTP until it has a name. Once a domain's A record points at the droplet:

1. Change `:80 {` in the `Caddyfile` to `yourdomain.com {`
2. `docker compose restart web`

Caddy requests a Let's Encrypt certificate on first request and renews it from then on. Nothing
else changes — the cert lives in the `caddy-data` volume, which is why that volume exists.

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
- **No bot opens an inbound port.** Long polling is outbound-only, so no bot has a listening
  socket to attack. The `web` service does listen on 80/443, but it is a separate container that
  serves static files and has no access to any bot's volume, token or database — compromising it
  reveals a public web page.
- The bot **cannot move anyone's money**. It holds no keys and has no wallet — the worst a stolen
  token achieves is impersonating the bot in chat, which is fixed with `/revoke` in BotFather.
- Logs record the **command word only**, never message text.
