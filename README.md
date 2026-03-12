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

## Endpoint

- `GET /check`: jalankan pengecekan tiket secara manual.
