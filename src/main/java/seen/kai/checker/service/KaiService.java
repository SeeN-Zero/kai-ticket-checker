package seen.kai.checker.service;

import com.ruiyun.jvppeteer.api.core.Browser;
import com.ruiyun.jvppeteer.api.core.Page;
import com.ruiyun.jvppeteer.cdp.core.Puppeteer;
import com.ruiyun.jvppeteer.cdp.entities.GoToOptions;
import com.ruiyun.jvppeteer.cdp.entities.LaunchOptions;
import com.ruiyun.jvppeteer.cdp.entities.Viewport;
import com.ruiyun.jvppeteer.common.PuppeteerLifeCycle;
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
import seen.kai.telegram.service.TelegramApiService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@ApplicationScoped
public class KaiService {
    private static final Logger LOG = Logger.getLogger(KaiService.class);
    private static final Pattern CF_RAY_PATTERN = Pattern.compile("Ray ID:\\s*<code>([a-zA-Z0-9]+)</code>");
    private static final DateTimeFormatter KAI_DATE_FORMATTER = DateTimeFormatter.ofPattern("d-MMMM-uuuu", Locale.forLanguageTag("id-ID"));

    @Inject
    SubscriptionService subscriptionService;

    @Inject
    StationService stationService;

    @Inject
    TelegramApiService telegramApi;

    @ConfigProperty(name = "kai.puppeteer.headless", defaultValue = "true")
    boolean headless;

    @ConfigProperty(name = "kai.puppeteer.user-data-dir", defaultValue = ".puppeteer-profile")
    String userDataDir;

    @ConfigProperty(name = "kai.puppeteer.manual-wait-seconds", defaultValue = "180")
    int manualWaitSeconds;

    @ConfigProperty(name = "kai.puppeteer.max-tabs", defaultValue = "5")
    int maxTabs;

    @ConfigProperty(name = "kai.puppeteer.executable-path")
    java.util.Optional<String> executablePath;

    @ConfigProperty(name = "kai.cloudflare.max-retries", defaultValue = "3")
    int cloudflareMaxRetries;

    @ConfigProperty(name = "kai.cloudflare.retry-delay-seconds", defaultValue = "20")
    int cloudflareRetryDelaySeconds;

    public void checkTicketFromDatabase(String chatId) {
        List<TicketSubscription> subscriptions;
        if (chatId.isBlank()) {
            subscriptions = subscriptionService.findAllWithChats();
        } else {
            subscriptions = subscriptionService.findAllByChatId(chatId);
        }

        if (subscriptions.isEmpty()) {
            LOG.info("Tidak ada subscription di database. Scheduler skip.");
            return;
        }

        String finalExecutablePath = executablePath.orElse(null);
        if (finalExecutablePath == null || finalExecutablePath.isBlank()) {
            finalExecutablePath = findDefaultChromePath();
        }

        LOG.infof("Launching Puppeteer browser. Final executablePath=%s, headless=%b, userDataDir=%s", 
                finalExecutablePath != null ? finalExecutablePath : "DEFAULT (Searching...)", headless, userDataDir);
        
        // Buat list argumen yang bisa dimodifikasi
        List<String> browserArgs = new ArrayList<>(List.of(
                "--disable-blink-features=AutomationControlled",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-setuid-sandbox",
                "--window-size=1366,768",
                "--remote-allow-origins=*",
                "--disable-infobars",
                "--no-first-run",
                "--disable-notifications",
                "--password-store=basic",
                "--use-mock-keychain",
                "--disable-features=LockProfile",
                "--disable-background-networking",
                "--disable-extensions",
                "--remote-debugging-port=0",
                "--disable-gpu",
                "--disable-software-rasterizer"
        ));

        // Jika userDataDir ada, tambahkan manual ke args alih-alih method .userDataDir()
        // karena di Windows seringkali bermasalah jika format path tidak tepat
        if (userDataDir != null && !userDataDir.isBlank()) {
            File profileDir = new File(userDataDir);
            if (!profileDir.exists()) {
                profileDir.mkdirs();
            }
            browserArgs.add("--user-data-dir=" + profileDir.getAbsolutePath());
        }

        LaunchOptions options = LaunchOptions.builder()
                .headless(headless)
                // .userDataDir(userDataDir) // Kita nonaktifkan method bawaan, gunakan args manual
                .dumpio(true)
                .timeout(60000)
                .args(browserArgs)
                .executablePath(finalExecutablePath)
                .build();

        Browser browser = null;
        ExecutorService executor = Executors.newFixedThreadPool(maxTabs);
        try {
            if (finalExecutablePath != null) {
                File exeFile = new File(finalExecutablePath);
                if (!exeFile.exists()) {
                    LOG.errorf("Executable path yang ditentukan TIDAK ADA: %s. Puppeteer mungkin akan gagal.", finalExecutablePath);
                } else {
                    LOG.infof("Menggunakan executable browser di: %s", finalExecutablePath);
                }
            } else {
                LOG.info("Executable path tidak ditentukan dan tidak ditemukan di path standar. Menggunakan mekanisme download jvppeteer...");
            }
            
            browser = Puppeteer.launch(options);
            LOG.infof("Puppeteer browser started. headless=%s userDataDir=%s maxTabs=%d", headless, userDataDir, maxTabs);

            final Browser finalBrowser = browser;
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (TicketSubscription subscription : subscriptions) {
                List<String> chatIds = extractChatIds(subscription);
                if (chatIds.isEmpty()) continue;

                Destination origination = toDestination(subscription.getOrigination(), subscription.getOriginationName());
                Destination destination = toDestination(subscription.getDestination(), subscription.getDestinationName());
                LocalDate startDate = subscription.getStartDate();
                LocalDate endDate = subscription.getEndDate();

                for (LocalDate targetDate = startDate; !targetDate.isAfter(endDate); targetDate = targetDate.plusDays(1)) {
                    final LocalDate dateToProcess = targetDate;
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        Page page = null;
                        try {
                            page = finalBrowser.newPage();
                            page.setViewport(new Viewport(1366, 768));
                            checkSingleDate(page, subscription, dateToProcess, origination, destination, chatIds);
                        } catch (Exception e) {
                            LOG.errorf(e, "Error checking date %s for subscription %d", dateToProcess, subscription.getId());
                        } finally {
                            if (page != null) {
                                try { page.close(); } catch (Exception ignore) {}
                            }
                        }
                    }, executor);
                    futures.add(future);
                }
            }

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        } catch (Exception e) {
            LOG.error("Puppeteer tidak bisa dijalankan", e);
            notifyFailure(subscriptions);
        } finally {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(1, TimeUnit.MINUTES)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
            }
            if (browser != null) {
                try { browser.close(); } catch (Exception ignore) {}
            }
        }
    }

    private String findDefaultChromePath() {
        String[] paths = {
                "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
                "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe",
                "/usr/bin/google-chrome",
                "/usr/bin/chromium",
                "/usr/bin/chromium-browser"
        };
        for (String path : paths) {
            if (new File(path).exists()) {
                return path;
            }
        }
        return null;
    }

    private void checkSingleDate(Page page, TicketSubscription subscription, LocalDate targetDate,
                                 Destination origination, Destination destination, List<String> chatIds) {
        String kaiDate = KAI_DATE_FORMATTER.format(targetDate);
        String url = buildKaiUrl(kaiDate, origination, destination);

        LOG.infof(
                "Checking subscription id=%d date=%s route=%s->%s maxPrice=%d",
                subscription.getId(), kaiDate, origination.code(), destination.code(), subscription.getMaxPrice()
        );

        Document doc = fetchScheduleDocumentWithRetry(page, kaiDate, destination, url, chatIds);
        if (doc == null) {
            return;
        }

        Elements trains = doc.select(".data-block");
        if (trains.isEmpty()) {
            LOG.warnf("Tidak menemukan data kereta untuk tanggal %s tujuan %s", kaiDate, destination.name());
            return;
        }

        String message = buildResultMessage(kaiDate, origination, destination, subscription.getMaxPrice(), trains);
        sendMessageToChats(message, chatIds);
    }

    private void checkSubscription(Page page, TicketSubscription subscription) {
        // Method ini tidak lagi digunakan, diganti oleh logika paralel di checkTicketFromDatabase
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
                GoToOptions goToOptions = new GoToOptions();
                goToOptions.setWaitUntil(List.of(PuppeteerLifeCycle.domcontentloaded));
                page.goTo(url, goToOptions);
                try { Thread.sleep(2500); } catch (InterruptedException ignore) {}
            } catch (Exception e) {
                LOG.errorf(e, "Puppeteer gagal navigate untuk tanggal %s tujuan %s (attempt %d/%d)",
                        date, destination.name(), attempt, maxAttempts);

                if (attempt >= maxAttempts) {
                    sendCloudflareAlert(chatIds, date, destination, url, "Puppeteer gagal navigasi setelah retry.", "-");
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
            try { Thread.sleep(5000); } catch (InterruptedException ignore) {}
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
        Exception lastException = new Exception("Gagal membaca konten halaman.");
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                return page.content();
            } catch (Exception e) {
                lastException = e;
                String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
                boolean navigatingNow = message.contains("page is navigating")
                        || message.contains("changing the content")
                        || message.contains("target closed");

                if (message.contains("target closed")) {
                    break;
                }

                if (!navigatingNow || attempt == 5) {
                    break;
                }

                try { Thread.sleep(1200); } catch (InterruptedException ignore) {}
            }
        }

        if (lastException instanceof RuntimeException) {
            throw (RuntimeException) lastException;
        }
        throw new RuntimeException(lastException);
    }

    public void sendMessageToChats(String message, List<String> chatIds) {
        if (!telegramApi.isEnabled()) {
            return;
        }

        for (String chatId : chatIds) {
            telegramApi.sendMessage(chatId, message, null);
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

    private void notifyFailure(List<TicketSubscription> subscriptions) {
        Set<String> chatIds = new LinkedHashSet<>();
        for (TicketSubscription subscription : subscriptions) {
            chatIds.addAll(extractChatIds(subscription));
        }
        sendMessageToChats("Ticket checker gagal: Puppeteer tidak bisa dijalankan.", new ArrayList<>(chatIds));
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
            Station station = stationService.findStationsByCode(normalizedCode).orElse(null);
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
