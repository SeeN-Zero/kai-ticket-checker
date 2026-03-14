# kai-ticket-checker

Checker ketersediaan tiket KAI berbasis Quarkus + Playwright dengan notifikasi Telegram.

## Fitur

- Cek tiket berkala via scheduler Quarkus.
- Trigger manual via endpoint `GET /check`.
- Retry otomatis saat challenge Cloudflare terdeteksi.
- Notifikasi hasil cek ke Telegram.

## Prasyarat

- Java 21
- Maven Wrapper (`mvnw` sudah tersedia di repo)

## Menjalankan aplikasi

Mode development:

```bash
./mvnw quarkus:dev
```

Build jar:

```bash
./mvnw package
```

Run hasil build:

```bash
java -jar target/kai-ticket-checker-1.0-SNAPSHOT-runner.jar
```

## Docker

Build base image (sekali saja, kecuali ada perubahan dependency/browser):

```bash
docker build -f docker/base/Dockerfile -t kai-ticket-checker-base:latest .
```

Build app image di atas base image:

```bash
docker build -f docker/Dockerfile --build-arg BASE_IMAGE=kai-ticket-checker-base:latest -t kai-ticket-checker:latest .
```

Run 2 container sekaligus (A dan B) via Compose (otomatis pakai base image di atas):

```bash
docker compose -f docker/docker-compose.yml up -d --build
```

`docker/docker-compose.yml` sudah menyiapkan:
- `kai-checker-a`: tanggal 25-30, `KM -> PSE/GMR`.
- `kai-checker-b`: tanggal 15-19, `PSE -> KM`.
- profile Playwright terpisah (`/profiles/a` dan `/profiles/b`) agar tidak bentrok.
- build arg `BASE_IMAGE` diambil dari env `KAI_BASE_IMAGE` (default: `kai-ticket-checker-base:latest`).

Sebelum `docker compose -f docker/docker-compose.yml up`, set env host:

```bash
export KAI_TELEGRAM_BOT_TOKEN=your_bot_token
export KAI_TELEGRAM_CHAT_ID=your_chat_id
```

PowerShell:

```powershell
$env:KAI_TELEGRAM_BOT_TOKEN="your_bot_token"
$env:KAI_TELEGRAM_CHAT_ID="your_chat_id"
```

## Konfigurasi

Semua credential disimpan via properti konfigurasi atau environment variable.

### Credential Telegram (wajib)

- `kai.telegram.bot-token` (env: `KAI_TELEGRAM_BOT_TOKEN`)
- `kai.telegram.chat-id` (env: `KAI_TELEGRAM_CHAT_ID`)

Jika credential belum diisi, aplikasi tetap berjalan tetapi notifikasi Telegram tidak akan dikirim.

### Properti utama

- `kai.playwright.headless` (default: `false`)
- `kai.playwright.user-data-dir` (default: `.playwright-profile`)
- `kai.playwright.manual-wait-seconds` (default: `180`)
- `kai.cloudflare.max-retries` (default: `3`)
- `kai.cloudflare.retry-delay-seconds` (default: `20`)
- `kai.route.origination` (default: `KM:KEBUMEN`)
- `kai.route.destinations` (default: `PSE:PASARSENEN,GMR:GAMBIR`)
- `kai.alert.max-price-rupiah` (default: `500000`)
- `kai.scheduler.every` (default: `30m`)
- `kai.scheduler.enabled` (default: `true`)

Format `kai.route.origination`: `KODE:Nama`.
Format `kai.route.destinations`: `KODE:Nama,KODE:Nama` (pisahkan dengan koma).

Contoh set credential via environment variable:

```bash
export KAI_TELEGRAM_BOT_TOKEN="your_bot_token"
export KAI_TELEGRAM_CHAT_ID="your_chat_id"
./mvnw quarkus:dev
```

Contoh ubah interval scheduler ke 15 menit:

```bash
export KAI_SCHEDULER_EVERY="15m"
./mvnw quarkus:dev
```

PowerShell:

```powershell
$env:KAI_SCHEDULER_EVERY="15m"
./mvnw quarkus:dev
```

## Endpoint

- `GET /check`: jalankan pengecekan tiket secara manual.
