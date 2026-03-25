#!/bin/bash

# Konfigurasi
IMAGE_NAME="kai-ticket-checker"
DOCKER_DIR="docker"

echo "=== Memulai proses build/deploy di server ==="

# Pindah ke direktori deploy
cd "$(dirname "$0")/.." || exit 1

# 1. Build Image Aplikasi
echo "Membangun image aplikasi: ${IMAGE_NAME}..."
docker compose -f "${DOCKER_DIR}/docker-compose.yml" build

# 2. Restart Container
echo "Merestart container menggunakan Docker Compose..."
docker compose -f "${DOCKER_DIR}/docker-compose.yml" up -d
docker image prune -a -f

echo "=== Proses selesai ==="
