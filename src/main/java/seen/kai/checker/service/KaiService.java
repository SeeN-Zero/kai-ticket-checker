package seen.kai.checker.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import seen.kai.checker.service.StationService.Station;
import seen.kai.subscription.entity.TelegramChat;
import seen.kai.subscription.entity.TicketSubscription;
import seen.kai.subscription.service.SubscriptionService;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class KaiService {
    private static final Logger LOG = Logger.getLogger(KaiService.class);
    private static final Pattern CF_RAY_PATTERN = Pattern.compile("Ray ID:\\s*<code>([a-zA-Z0-9]+)</code>");
    private static final DateTimeFormatter KAI_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("d-MMMM-uuuu", Locale.forLanguageTag("id-ID"));

    @Inject
    SubscriptionService subscriptionService;

    @Inject
    StationService stationService;

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

    public void checkTicketFromDatabase() {
        List<TicketSubscription> subscriptions = subscriptionService.findAllWithChats();
        if (subscriptions.isEmpty()) {
            LOG.info("Tidak ada subscription di database. Scheduler skip.");
            return;
        }

        try (Playwright playwright = Playwright.create()) {
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

                for (TicketSubscription subscription : subscriptions) {
                    checkSubscription(page, subscription);
                }
            }
        } catch (PlaywrightException e) {
            LOG.error("Playwright tidak bisa dijalankan", e);
            notifyPlaywrightFailure(subscriptions);
        } catch (Exception e) {
            LOG.error("Error checking ticket from database", e);
        }
    }

    private void checkSubscription(Page page, TicketSubscription subscription) {
        List<String> chatIds = extractChatIds(subscription);
        if (chatIds.isEmpty()) {
            LOG.warnf("Subscription id=%d tidak punya chat_id, dilewati.", subscription.getId());
            return;
        }

        Destination origination = toDestination(subscription.getOrigination(), subscription.getOriginationName());
        Destination destination = toDestination(subscription.getDestination(), subscription.getDestinationName());
        LocalDate startDate = subscription.getStartDate();
        LocalDate endDate = subscription.getEndDate();

        for (LocalDate targetDate = startDate; !targetDate.isAfter(endDate); targetDate = targetDate.plusDays(1)) {
            String kaiDate = KAI_DATE_FORMATTER.format(targetDate);
            String url = buildKaiUrl(kaiDate, origination, destination);

            LOG.infof(
                    "Checking subscription id=%d date=%s route=%s->%s maxPrice=%d",
                    subscription.getId(), kaiDate, origination.code(), destination.code(), subscription.getMaxPrice()
            );

            Document doc = fetchScheduleDocumentWithRetry(page, kaiDate, destination, url, chatIds);
            if (doc == null) {
                continue;
            }

            Elements trains = doc.select(".data-block");
            if (trains.isEmpty()) {
                LOG.warnf("Tidak menemukan data kereta untuk tanggal %s tujuan %s", kaiDate, destination.name());
                continue;
            }

            String message = buildResultMessage(kaiDate, origination, destination, subscription.getMaxPrice(), trains);
            sendMessageToChats(message, chatIds);
            page.waitForTimeout(5000);
        }
    }

    private String buildResultMessage(
            String date,
            Destination origination,
            Destination destination,
            int maxPrice,
            Elements trains
    ) {
        boolean foundTicket = false;
        StringBuilder message = new StringBuilder();
        message.append("Tanggal: ").append(date).append("\n")
                .append("Rute: ").append(origination.code()).append(" -> ").append(destination.code()).append("\n")
                .append("Maks harga: Rp").append(formatRupiah(maxPrice)).append("\n\n");

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
            boolean withinPrice = priceRupiah > 0 && priceRupiah <= maxPrice;

            if (!soldOut && withinPrice) {
                foundTicket = true;
                message.append("TIKET TERSEDIA\n")
                        .append("Kereta: ").append(name).append("\n")
                        .append("Berangkat: ").append(departureTime).append("\n")
                        .append("Tiba: ").append(arrivalTime).append("\n")
                        .append("Harga: ").append(price).append("\n")
                        .append("Status: ").append(status).append("\n\n");
            }
        }

        if (!foundTicket) {
            message.append("Tidak ada tiket tersedia dengan harga <= Rp")
                    .append(formatRupiah(maxPrice))
                    .append(".");
        }

        return message.toString();
    }

    private Document fetchScheduleDocumentWithRetry(
            Page page,
            String date,
            Destination destination,
            String url,
            List<String> chatIds
    ) {
        int maxAttempts = Math.max(1, cloudflareMaxRetries + 1);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                page.navigate(url, new Page.NavigateOptions().setWaitUntil(WaitUntilState.DOMCONTENTLOADED));
                page.waitForTimeout(2500);
            } catch (PlaywrightException e) {
                LOG.errorf(e, "Playwright gagal navigate untuk tanggal %s tujuan %s (attempt %d/%d)",
                        date, destination.name(), attempt, maxAttempts);

                if (attempt >= maxAttempts) {
                    sendCloudflareAlert(chatIds, date, destination, url, "Playwright gagal navigasi setelah retry.", "-");
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

            boolean solved = !headless && waitForManualChallengeSolve(page, date, destination, url, chatIds);
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
                sendCloudflareAlert(chatIds, date, destination, url, "Managed challenge/Turnstile tetap muncul setelah retry.", rayId);
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

    private boolean waitForManualChallengeSolve(
            Page page,
            String date,
            Destination destination,
            String url,
            List<String> chatIds
    ) {
        if (manualWaitSeconds <= 0) {
            return false;
        }

        sendMessageToChats(
                "Cloudflare challenge terdeteksi.\n"
                        + "Tanggal: " + date + "\n"
                        + "Tujuan: " + destination.name() + " (" + destination.code() + ")\n"
                        + "Selesaikan verifikasi manual di browser dalam " + manualWaitSeconds + " detik.\n"
                        + "URL: " + url,
                chatIds
        );

        long deadline = System.currentTimeMillis() + (manualWaitSeconds * 1000L);
        while (System.currentTimeMillis() < deadline) {
            page.waitForTimeout(5000);
            String html = safeReadPageContent(page);
            Document doc = Jsoup.parse(html);
            if (isSchedulePage(doc, html)) {
                sendMessageToChats("Verifikasi Cloudflare berhasil. Checker lanjut otomatis.", chatIds);
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

    public void sendMessageToChats(String message, List<String> chatIds) {
        if (telegramBotToken.isBlank()) {
            LOG.warn("Telegram bot token belum diatur. Set kai.telegram.bot-token.");
            return;
        }

        for (String chatId : chatIds) {
            sendMessage(message, chatId);
        }
    }

    private void sendMessage(String message, String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return;
        }

        try {
            String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8);
            String urlString = "https://api.telegram.org/bot" + telegramBotToken + "/sendMessage"
                    + "?chat_id=" + chatId + "&text=" + encodedMessage;

            URL url = URI.create(urlString).toURL();
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(10000);

            int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                LOG.infof("Telegram message sent successfully to chat_id=%s", chatId);
            } else {
                LOG.warnf("Telegram response code=%d for chat_id=%s", responseCode, chatId);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to send Telegram message to chat_id=%s", chatId);
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

    private void sendCloudflareAlert(
            List<String> chatIds,
            String date,
            Destination destination,
            String url,
            String status,
            String rayId
    ) {
        String message = "Cloudflare terdeteksi saat cek tiket\n"
                + "Tanggal: " + date + "\n"
                + "Tujuan: " + destination.name() + " (" + destination.code() + ")\n"
                + "URL: " + url + "\n"
                + "Status: " + status + "\n"
                + "Ray ID: " + rayId;
        sendMessageToChats(message, chatIds);
    }

    private void notifyPlaywrightFailure(List<TicketSubscription> subscriptions) {
        Set<String> chatIds = new LinkedHashSet<>();
        for (TicketSubscription subscription : subscriptions) {
            chatIds.addAll(extractChatIds(subscription));
        }
        sendMessageToChats("Ticket checker gagal: Playwright tidak bisa dijalankan.", new ArrayList<>(chatIds));
    }

    private List<String> extractChatIds(TicketSubscription subscription) {
        return subscription.getTelegramChats().stream()
                .map(TelegramChat::getChatId)
                .filter(value -> value != null && !value.isBlank())
                .distinct()
                .toList();
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

    private Destination toDestination(String stationCode, String stationName) {
        String normalizedCode = stationCode == null ? "" : stationCode.trim().toUpperCase(Locale.ROOT);
        String normalizedName = stationName == null ? "" : stationName.trim();

        if (normalizedName.isBlank() && !normalizedCode.isBlank()) {
            Station station = stationService.findByCode(normalizedCode).orElse(null);
            if (station != null && station.name() != null && !station.name().isBlank()) {
                normalizedName = station.name().trim();
            }
        }

        if (normalizedName.isBlank()) {
            normalizedName = normalizedCode;
        }
        return new Destination(normalizedCode, normalizedName);
    }

    private record Destination(String code, String name) {
    }
}
