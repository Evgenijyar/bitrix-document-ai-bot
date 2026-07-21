#!/usr/bin/env bash
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/bitrix-document-ai-bot}"
BRANCH="${BRANCH:-main}"
CONTAINER="${CONTAINER:-bitrix-document-ai-bot}"

cd "$APP_DIR"

echo "[1/6] Fetching repository..."
git fetch origin "$BRANCH"

echo "[2/6] Updating source code..."
git reset --hard "origin/$BRANCH"

echo "[3/6] Building Docker image..."
docker compose build --pull

echo "[4/6] Starting application..."
docker compose up -d --remove-orphans

echo "[5/6] Waiting for Docker health check..."
for attempt in $(seq 1 60); do
    state="$(docker inspect --format '{{.State.Status}}' "$CONTAINER" 2>/dev/null || true)"
    health="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$CONTAINER" 2>/dev/null || true)"

    if [[ "$health" == "healthy" ]]; then
        echo "Application is healthy."
        break
    fi

    if [[ "$state" == "exited" || "$state" == "dead" ]]; then
        echo "Container stopped unexpectedly: state=$state health=$health"
        docker logs --tail=300 "$CONTAINER" || true
        exit 1
    fi

    if [[ "$attempt" -eq 60 ]]; then
        echo "Application did not become healthy: state=$state health=$health"
        docker logs --tail=300 "$CONTAINER" || true
        exit 1
    fi

    sleep 2
done

echo "[6/6] Cleaning old images..."
docker image prune -f

echo
docker compose ps

echo
echo "Deployment completed successfully."
