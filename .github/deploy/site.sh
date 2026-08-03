#!/usr/bin/env bash
# Runs on the droplet, piped in over SSH by .github/workflows/deploy-site.yml.
#
# Deploying the site is a git pull: web/site is a read-only bind mount, so the new files are
# live the moment they land. `up -d web` is here only to start the server if it was down.
#
# Expects DIR in the environment. Keep this a real file rather than a heredoc in the workflow -
# an indented heredoc terminator inside YAML silently never terminates, and this way shellcheck
# and `bash -n` can both see it.
set -euo pipefail

cd "${DIR:?DIR not set}"

# --ff-only rather than `reset --hard`: if the droplet has drifted from main, stop and say so
# instead of throwing away whatever is on the box.
git pull --ff-only

docker compose up -d web

# A 200 from a stale file is still a 200, so check the body too. Caddy comes up fast, but not
# instantly, and a deploy that reports success before the server answers is worse than a slow one.
code=""
for _ in $(seq 1 10); do
  code=$(curl -s -o /tmp/deploy-check.html -w '%{http_code}' http://localhost/ || true)
  [ "$code" = "200" ] && break
  sleep 2
done

if [ "$code" != "200" ]; then
  echo "::error::Site returned '${code:-no response}' from inside the droplet"
  docker compose logs --tail 40 web
  exit 1
fi

if ! grep -q "Bot Tipper" /tmp/deploy-check.html; then
  echo "::error::Served a 200, but the page is not the site"
  exit 1
fi

rm -f /tmp/deploy-check.html
echo "Site is up: $(git rev-parse --short HEAD)"
