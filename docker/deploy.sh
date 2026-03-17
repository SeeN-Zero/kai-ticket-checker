#!/bin/bash

# Konfigurasi
IMAGE_NAME="kai-ticket-checker"
BASE_IMAGE="kai-ticket-checker-base:latest"
DOCKER_DIR="docker"

echo "=== Memulai proses build/deploy di server ==="

# Pindah ke direktori deploy
cd "$(dirname "$0")/.." || exit 1

# 1. Cek dan Build Base Image jika belum ada
echo "Memeriksa base image: ${BASE_IMAGE}..."
if [[ "$(docker images -q ${BASE_IMAGE} 2> /dev/null)" == "" ]]; then
  echo "Base image tidak ditemukan. Membangun base image..."
  docker build -t "${BASE_IMAGE}" -f "${DOCKER_DIR}/base/Dockerfile" .
else
  echo "Base image sudah tersedia."
fi

# 2. Build Image Aplikasi
echo "Membangun image aplikasi: ${IMAGE_NAME}..."
docker compose -f "${DOCKER_DIR}/docker-compose.yml" build --build-arg BASE_IMAGE="${BASE_IMAGE}"

# 3. Restart Container
echo "Merestart container menggunakan Docker Compose..."
docker compose -f "${DOCKER_DIR}/docker-compose.yml" up -d

echo "=== Proses selesai ==="
