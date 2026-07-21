#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/bitrix-document-ai-bot}"
BRANCH="${BRANCH:-main}"

cd "$APP_DIR"
git fetch origin "$BRANCH"
git reset --hard "origin/$BRANCH"
docker compose build --pull
docker compose up -d --remove-orphans
docker image prune -f
docker compose ps
