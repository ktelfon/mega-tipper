#!/usr/bin/env bash
# Runs on the droplet, piped in over SSH by .github/workflows/deploy-bot.yml.
#
# The droplet never builds anything: CI publishes an image and this points the box at it.
# Expects DIR and TAG in the environment.
set -euo pipefail

cd "${DIR:?DIR not set}"
: "${TAG:?TAG not set}"

git pull --ff-only

# Written into .env rather than exported, so the choice outlives this SSH session. Someone
# typing `docker compose up` on the box next month gets the image this deploy picked, instead
# of silently drifting back to :latest.
if grep -q '^TIPBOT_IMAGE_TAG=' .env; then
  sed -i "s|^TIPBOT_IMAGE_TAG=.*|TIPBOT_IMAGE_TAG=${TAG}|" .env
else
  echo "TIPBOT_IMAGE_TAG=${TAG}" >> .env
fi

docker compose pull
docker compose up -d

# A container is "Up" for the couple of seconds before it exits on a bad wallet address, so
# give it long enough to fail before believing it.
sleep 15

running=$(docker compose ps --status running --format '{{.Service}}' || true)
bots=$(echo "$running" | grep -v '^web$' | grep -v '^$' || true)

if [ -z "$bots" ]; then
  echo "::error::No bot service is running after the deploy"
  docker compose ps -a
  docker compose logs --tail 60
  exit 1
fi

# Restarting means crash-looping. The usual cause is a customers/*.yaml the unprivileged
# container user cannot read - see the file-permissions section in DEPLOY.md.
if docker compose ps --format '{{.Service}} {{.State}}' | grep -i restarting; then
  echo "::error::A service is crash-looping"
  docker compose logs --tail 60
  exit 1
fi

# Old images are what fills a small disk a few months from now.
docker image prune -f >/dev/null

echo "Running ${TAG}:"
echo "$bots" | sed 's/^/  /'
