import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class KaiService {
    private static final Logger LOG = Logger.getLogger(KaiService.class);
    private static final Destination DEFAULT_ORIGINATION = new Destination("KM", "KEBUMEN");
    private static final List<Destination> DEFAULT_DESTINATIONS = List.of(
            new Destination("PSE", "PASARSENEN"),
            new Destination("GMR", "GAMBIR")
    );
    private static final Pattern CF_RAY_PATTERN = Pattern.compile("Ray ID:\\s*<code>([a-zA-Z0-9]+)</code>");

    @ConfigProperty(name = "kai.playwright.headless", defaultValue = "false")
    boolean headless;

    @ConfigProperty(name = "kai.playwright.user-data-dir", defaultValue = ".playwright-profile")
    String userDataDir;

    @ConfigProperty(name = "kai.playwright.manual-wait-seconds", defaultValue = "180")
    int manualWaitSeconds;

    @ConfigProperty(name = "kai.cloudflare.max-retries", defaultValue = "3")
    int cloudflareMaxRetries;

    @ConfigProperty(name = "kai.cloudflare.retry-delay-seconds", defaultValue = "20")
    int cloudflareRetryDelaySeconds;

    @ConfigProperty(name = "kai.telegram.bot-token", defaultValue = "")
    String telegramBotToken;

    @ConfigProperty(name = "kai.telegram.chat-id", defaultValue = "")
    String telegramChatId;

    @ConfigProperty(name = "kai.route.origination", defaultValue = "KM:KEBUMEN")
    String originationConfig;

    @ConfigProperty(name = "kai.route.destinations", defaultValue = "PSE:PASARSENEN,GMR:GAMBIR")
    String destinationsConfig;

    @ConfigProperty(name = "kai.alert.max-price-rupiah", defaultValue = "500000")
    int maxAlertPriceRupiah;

    public void checkTicket() {
        try (Playwright playwright = Playwright.create()) {
            Destination origination = parseOrigination(originationConfig);
            List<Destination> destinations = parseDestinations(destinationsConfig);
            Path profileDir = Paths.get(userDataDir).toAbsolutePath();
            try (BrowserContext context = playwright.chromium().launchPersistentContext(
                    profileDir,
                    new BrowserType.LaunchPersistentContextOptions()
                            .setHeadless(headless)
                            .setArgs(List.of(
                                    "--disable-blink-features=AutomationControlled",
                                    "--no-sandbox",
                                    "--disable-dev-shm-usage"
                            ))
                            .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122 Safari/537.36")
                            .setLocale("id-ID")
                            .setViewportSize(1366, 768)
            )) {
                Browser browser = context.browser();
                if (browser != null) {
                    LOG.infof("Playwright browser started. headless=%s profileDir=%s", headless, profileDir);
                }

                Page page = context.pages().isEmpty() ? context.newPage() : context.pages().getFirst();
                page.setDefaultTimeout(30000);

                for (int day = 25; day <= 30; day++) {
                    for (Destination destination : destinations) {
                        String date = day + "-Maret-2026";
                        String url = buildKaiUrl(date, origination, destination);

                        LOG.infof("Checking date: %s, destination: %s", date, destination.name());

                        Document doc = fetchScheduleDocumentWithRetry(page, date, destination, url);
                        if (doc == null) {
                            continue;
                        }

                        Elements trains = doc.select(".data-block");
                        if (trains.isEmpty()) {
                            LOG.warnf("Tidak menemukan data kereta untuk tanggal %s tujuan %s", date, destination.name());
                            continue;
                        }

                        boolean foundTicket = false;
                        StringBuilder message = new StringBuilder();
                        message.append("Tanggal: ").append(date).append("\n")
                                .append("Tujuan: ").append(destination.name())
                                .append(" (").append(destination.code()).append(")")
                                .append("\n\n");

                        for (Element train : trains) {
                            String name = train.select(".name").text();
                            String price = train.select(".price").text();
                            String status = train.select(".sisa-kursi").text();
                            String departureTime = extractFirstText(train,
                                    ".times.time-start",
                                    ".time-start",
                                    "input[name=timestart]",
                                    ".jam-berangkat",
                                    ".departure-time",
                                    ".time-departure",
                                    ".departure .time",
                                    ".schedule .departure");
                            String arrivalTime = extractFirstText(train,
                                    ".times.time-end",
                                    ".time-end",
                                    "input[name=timeend]",
                                    ".jam-tiba",
                                    ".arrival-time",
                                    ".time-arrival",
                                    ".arrival .time",
                                    ".schedule .arrival");
                            int priceRupiah = parseRupiah(price);
                            boolean soldOut = status.toLowerCase(Locale.ROOT).contains("habis");
                            boolean withinPrice = priceRupiah > 0 && priceRupiah <= maxAlertPriceRupiah;

                            if (!soldOut && withinPrice) {
                                foundTicket = true;
                                message.append("\uD83D\uDEA8 TIKET TERSEDIA\n")
                                        .append("Kereta: ").append(name).append("\n")
                                        .append("Berangkat: ").append(departureTime).append("\n")
                                        .append("Tiba: ").append(arrivalTime).append("\n")
                                        .append("Harga: ").append(price).append("\n")
                                        .append("Status: ").append(status).append("\n\n");
                            }
                        }

                        if (!foundTicket) {
                            message.append("❌ Tidak ada tiket tersedia dengan harga <= Rp")
                                    .append(formatRupiah(maxAlertPriceRupiah))
                                    .append(".");
                        }

                        sendMessage(message.toString());
                        page.waitForTimeout(5000);
                    }
                }
            }
        } catch (PlaywrightException e) {
            LOG.error("Playwright tidak bisa dijalankan", e);
            sendMessage("⚠️ Ticket checker gagal: Playwright tidak bisa dijalankan.");
        } catch (Exception e) {
            LOG.error("Error checking ticket", e);
        }
    }

    private Document fetchScheduleDocumentWithRetry(Page page, String date, Destination destination, String url) {
        int maxAttempts = Math.max(1, cloudflareMaxRetries + 1);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(2500);
            } catch (PlaywrightException e) {
                LOG.errorf(e, "Playwright gagal navigate untuk tanggal %s tujuan %s (attempt %d/%d)",
                        date, destination.name(), attempt, maxAttempts);

                if (attempt >= maxAttempts) {
                    sendCloudflareAlert(date, destination, url, "Playwright gagal navigasi setelah retry.", "-");
                    return null;
                }

                waitBeforeRetry(attempt, date, destination);
                continue;
            }

            String html = safeReadPageContent(page);
            Document doc = Jsoup.parse(html);
            if (isSchedulePage(doc, html)) {
                return doc;
            }

            String rayId = extractCloudflareRayId(html);
            LOG.warnf("Cloudflare challenge terdeteksi untuk tanggal %s tujuan %s (attempt %d/%d)",
                    date, destination.name(), attempt, maxAttempts);

            boolean solved = !headless && waitForManualChallengeSolve(page, date, destination, url);
            if (solved) {
                html = safeReadPageContent(page);
                doc = Jsoup.parse(html);
                if (isSchedulePage(doc, html)) {
                    LOG.infof("Cloudflare challenge terselesaikan manual untuk tanggal %s tujuan %s", date, destination.name());
                    return doc;
                }
                rayId = extractCloudflareRayId(html);
            }

            if (attempt >= maxAttempts) {
                sendCloudflareAlert(date, destination, url, "Managed challenge/Turnstile tetap muncul setelah retry.", rayId);
                return null;
            }

            waitBeforeRetry(attempt, date, destination);
        }

        return null;
    }

    private void waitBeforeRetry(int attempt, String date, Destination destination) {
        int delayMs = Math.max(1, cloudflareRetryDelaySeconds) * 1000;
        LOG.infof("Retrying request untuk tanggal %s tujuan %s dalam %d detik (attempt berikutnya: %d)",
                date, destination.name(), cloudflareRetryDelaySeconds, attempt + 1);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("Retry delay interrupted untuk tanggal %s tujuan %s", date, destination.name());
        }
    }

    private boolean waitForManualChallengeSolve(Page page, String date, Destination destination, String url) {
        if (manualWaitSeconds <= 0) {
            return false;
        }

        sendMessage("⚠️ Cloudflare challenge terdeteksi.\n"
                + "Tanggal: " + date + "\n"
                + "Tujuan: " + destination.name() + " (" + destination.code() + ")\n"
                + "Silakan selesaikan verifikasi manual di jendela browser dalam "
                + manualWaitSeconds + " detik.\n"
                + "URL: " + url);

        long deadline = System.currentTimeMillis() + (manualWaitSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            page.waitForTimeout(5000);
            String html = safeReadPageContent(page);
            Document doc = Jsoup.parse(html);
            if (isSchedulePage(doc, html)) {
                sendMessage("✅ Verifikasi Cloudflare berhasil. Checker lanjut otomatis.");
                return true;
            }
        }

        return false;
    }

    private String safeReadPageContent(Page page) {
        PlaywrightException lastException = new PlaywrightException("Gagal membaca konten halaman.");
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                page.waitForLoadState(
                        LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(5000)
                );
                return page.content();
            } catch (PlaywrightException e) {
                lastException = e;
                String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
                boolean navigatingNow = message.contains("page is navigating")
                        || message.contains("changing the content");

                if (!navigatingNow || attempt == 5) {
                    break;
                }

                page.waitForTimeout(1200);
            }
        }

        throw lastException;
    }

    public void sendMessage(String message) {
        if (telegramBotToken.isBlank() || telegramChatId.isBlank()) {
            LOG.warn("Telegram credential belum diatur. Set kai.telegram.bot-token dan kai.telegram.chat-id.");
            return;
        }

        try {
            String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8);
            String urlString = "https://api.telegram.org/bot" + telegramBotToken + "/sendMessage"
                    + "?chat_id=" + telegramChatId + "&text=" + encodedMessage;

            URL url = URI.create(urlString).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                LOG.info("Telegram message sent successfully");
            } else {
                LOG.info("Telegram response code: " + responseCode);
            }
        } catch (Exception e) {
            LOG.error("Failed to send Telegram message", e);
        }
    }

    private String buildKaiUrl(String date, Destination origination, Destination destination) {
        return "https://booking.kai.id/?origination="
                + URLEncoder.encode(origination.code(), StandardCharsets.UTF_8)
                + "&flexdatalist-origination="
                + URLEncoder.encode(origination.name(), StandardCharsets.UTF_8)
                + "&destination="
                + URLEncoder.encode(destination.code(), StandardCharsets.UTF_8)
                + "&flexdatalist-destination="
                + URLEncoder.encode(destination.name(), StandardCharsets.UTF_8)
                + "&tanggal="
                + URLEncoder.encode(date, StandardCharsets.UTF_8)
                + "&adult=1&infant=0&submit="
                + URLEncoder.encode("Cari & Pesan Tiket", StandardCharsets.UTF_8);
    }

    private Destination parseOrigination(String config) {
        Destination parsed = parseRoute(config);
        if (parsed == null) {
            LOG.warnf("Format kai.route.origination tidak valid: '%s'. Menggunakan default origination.", config);
            return DEFAULT_ORIGINATION;
        }
        return parsed;
    }

    private List<Destination> parseDestinations(String config) {
        if (config == null || config.isBlank()) {
            LOG.warn("Config kai.route.destinations kosong. Menggunakan default destination.");
            return DEFAULT_DESTINATIONS;
        }

        List<Destination> parsed = config.lines()
                .flatMap(line -> Stream.of(line.split(",")))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(this::parseRoute)
                .filter(Objects::nonNull)
                .toList();

        if (parsed.isEmpty()) {
            LOG.warnf("Format kai.route.destinations tidak valid: '%s'. Menggunakan default destination.", config);
            return DEFAULT_DESTINATIONS;
        }

        return parsed;
    }

    private Destination parseRoute(String config) {
        if (config == null || config.isBlank()) {
            return null;
        }

        String[] parts = config.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            return null;
        }

        return new Destination(parts[0].trim(), parts[1].trim());
    }

    private boolean isSchedulePage(Document doc, String html) {
        String title = doc.title().toLowerCase(Locale.ROOT);
        String bodyText = doc.body().text().toLowerCase(Locale.ROOT);
        String lowerHtml = html.toLowerCase(Locale.ROOT);

        if (!doc.select(".data-block").isEmpty()) {
            return true;
        }

        int signals = 0;
        if (title.contains("just a moment") || title.contains("tunggu sebentar")) {
            signals++;
        }
        if (bodyText.contains("enable javascript and cookies to continue")
                || bodyText.contains("melakukan verifikasi keamanan")) {
            signals++;
        }
        if (lowerHtml.contains("cf_chl_opt") || lowerHtml.contains("/cdn-cgi/challenge-platform/")) {
            signals++;
        }
        if (lowerHtml.contains("ray id:") || lowerHtml.contains("cf-ray")) {
            signals++;
        }

        return signals < 2;
    }

    private void sendCloudflareAlert(String date, Destination destination, String url, String status, String rayId) {
        String message = "⚠️ Cloudflare terdeteksi saat cek tiket\n"
                + "Tanggal: " + date + "\n"
                + "Tujuan: " + destination.name() + " (" + destination.code() + ")\n"
                + "URL: " + url + "\n"
                + "Status: " + status + "\n"
                + "Ray ID: " + rayId;
        sendMessage(message);
    }

    private String extractCloudflareRayId(String html) {
        Matcher matcher = CF_RAY_PATTERN.matcher(html);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return "-";
    }

    private int parseRupiah(String text) {
        String digits = text.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private String formatRupiah(int value) {
        return String.format(Locale.forLanguageTag("id-ID"), "%,d", value).replace(',', '.');
    }

    private String extractFirstText(Element root, String... selectors) {
        for (String selector : selectors) {
            Elements elements = root.select(selector);
            for (Element element : elements) {
                String value = element.text();
                if (!value.isBlank()) {
                    return value.trim();
                }

                String inputValue = element.attr("value");
                if (!inputValue.isBlank()) {
                    return inputValue.trim();
                }
            }
        }
        return "-";
    }

    private record Destination(String code, String name) {
    }
}
