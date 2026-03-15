package seen.kai.telegram.service;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.telegram.telegrambots.bots.DefaultAbsSender;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import seen.kai.checker.service.StationService;
import seen.kai.subscription.dto.request.SubscriptionRequest;
import seen.kai.subscription.dto.response.SubscriptionResponse;
import seen.kai.subscription.entity.TicketSubscription;
import seen.kai.subscription.service.SubscriptionService;
import seen.kai.telegram.state.BotState;
import seen.kai.telegram.state.SubscriptionDraft;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@ApplicationScoped
public class TelegramBotService {
    private static final Logger LOG = Logger.getLogger(TelegramBotService.class);
    private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");
    private static final List<String> COMMON_STATIONS = List.of("KM", "PSE", "GMR");
    private static final Locale ID_LOCALE = Locale.forLanguageTag("id-ID");
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("d MMM", ID_LOCALE);

    private final SubscriptionService subscriptionService;
    private final StationService stationService;
    private final ConcurrentHashMap<String, SubscriptionDraft> sessions = new ConcurrentHashMap<>();

    @ConfigProperty(name = "kai.telegram.bot-token", defaultValue = "")
    String botToken;

    @ConfigProperty(name = "kai.subscription.password", defaultValue = "")
    String subscriptionPassword;

    private TelegramSender sender;

    public TelegramBotService(SubscriptionService subscriptionService, StationService stationService) {
        this.subscriptionService = subscriptionService;
        this.stationService = stationService;
    }

    @PostConstruct
    void init() {
        this.sender = new TelegramSender(botToken);
    }

    public void handleUpdate(Update update) {
        if (update == null) {
            return;
        }

        if (update.hasCallbackQuery()) {
            handleCallback(update.getCallbackQuery());
            return;
        }

        if (update.hasMessage()) {
            handleMessage(update.getMessage());
        }
    }

    private void handleCallback(CallbackQuery callbackQuery) {
        if (callbackQuery == null || callbackQuery.getMessage() == null) {
            return;
        }

        String callbackData = callbackQuery.getData();
        String chatId = String.valueOf(callbackQuery.getMessage().getChatId());
        Integer messageId = callbackQuery.getMessage().getMessageId();
        answerCallback(callbackQuery.getId());

        if (chatId.isBlank() || callbackData == null || callbackData.isBlank()) {
            return;
        }

        if ("menu:new".equals(callbackData)) {
            SubscriptionDraft draft = new SubscriptionDraft();
            if (subscriptionPassword != null && !subscriptionPassword.isBlank()) {
                draft.setState(BotState.WAITING_PASSWORD);
                draft.setPasswordVerified(false);
                sessions.put(chatId, draft);
                editMessage(chatId, messageId, "Masukkan password untuk membuat subscription:", null);
                return;
            }

            draft.setPasswordVerified(true);
            draft.setState(BotState.WAITING_START_MONTH);
            sessions.put(chatId, draft);
            editMessage(chatId, messageId, "Pilih bulan mulai (tahun otomatis):", monthKeyboard());
            return;
        }

        if ("act:cancel".equals(callbackData)) {
            sessions.remove(chatId);
            editMessage(chatId, messageId, "Flow dibatalkan.", mainMenuKeyboard());
            return;
        }

        SubscriptionDraft draft = sessions.computeIfAbsent(chatId, ignored -> new SubscriptionDraft());

        if (callbackData.startsWith("sm:")) {
            int month = parseIntSafely(callbackData.substring(3));
            int currentMonth = LocalDate.now().getMonthValue();
            if (month < currentMonth || month > 12) {
                editMessage(chatId, messageId, "Bulan tidak valid.", monthKeyboard());
                return;
            }
            draft.setStartMonth(month);
            draft.setState(BotState.WAITING_START_DATE);
            editMessage(chatId, messageId, "Pilih tanggal mulai:", startDateKeyboardForMonth(month));
            return;
        }

        if (callbackData.startsWith("sd:")) {
            LocalDate startDate = parseLocalDate(callbackData.substring(3));
            if (startDate == null || startDate.getYear() != LocalDate.now().getYear() || startDate.isBefore(LocalDate.now())) {
                editMessage(chatId, messageId, "Tanggal mulai tidak valid.", monthKeyboard());
                return;
            }
            draft.setStartDate(startDate);
            draft.setState(BotState.WAITING_END_DATE);
            editMessage(chatId, messageId, "Pilih tanggal akhir (maks 5 hari):", endDateKeyboard(startDate));
            return;
        }

        if (callbackData.startsWith("ed:")) {
            LocalDate endDate = parseLocalDate(callbackData.substring(3));
            LocalDate startDate = draft.getStartDate();
            if (endDate == null || startDate == null) {
                editMessage(chatId, messageId, "Tanggal akhir tidak valid.", monthKeyboard());
                return;
            }
            if (endDate.isBefore(startDate) || endDate.isAfter(startDate.plusDays(5)) || endDate.getYear() != LocalDate.now().getYear()) {
                editMessage(chatId, messageId, "Tanggal akhir harus dalam rentang 0..5 hari dari tanggal mulai.", endDateKeyboard(startDate));
                return;
            }
            draft.setEndDate(endDate);
            draft.setState(BotState.WAITING_ORIGINATION);
            editMessage(chatId, messageId, "Pilih origination:", stationKeyboard("org"));
            return;
        }

        if ("org:manual".equals(callbackData)) {
            draft.setState(BotState.WAITING_ORIGINATION_TEXT);
            editMessage(chatId, messageId, "Ketik kode origination manual (contoh: KM).", null);
            return;
        }
        if (callbackData.startsWith("org:")) {
            String code = callbackData.substring(4).trim().toUpperCase(Locale.ROOT);
            StationService.Station station = stationService.findByCode(code).orElse(null);
            draft.setOrigination(station != null ? station.code() : code);
            draft.setState(BotState.WAITING_DESTINATION);
            editMessage(chatId, messageId, "Pilih destination:", stationKeyboard("dst"));
            return;
        }

        if ("dst:manual".equals(callbackData)) {
            draft.setState(BotState.WAITING_DESTINATION_TEXT);
            editMessage(chatId, messageId, "Ketik kode destination manual (contoh: PSE).", null);
            return;
        }
        if (callbackData.startsWith("dst:")) {
            String code = callbackData.substring(4).trim().toUpperCase(Locale.ROOT);
            StationService.Station station = stationService.findByCode(code).orElse(null);
            draft.setDestination(station != null ? station.code() : code);
            draft.setState(BotState.WAITING_MAX_PRICE);
            editMessage(chatId, messageId, "Pilih max price (atau ketik angka):", maxPriceKeyboard());
            return;
        }

        if (callbackData.startsWith("mp:")) {
            if ("mp:manual".equals(callbackData)) {
                draft.setState(BotState.WAITING_MAX_PRICE);
                editMessage(chatId, messageId, "Ketik max price (contoh: 350000).", null);
                return;
            }
            int maxPrice = parseIntSafely(callbackData.substring(3));
            if (maxPrice <= 0) {
                editMessage(chatId, messageId, "max price tidak valid. Pilih tombol atau ketik angka.", maxPriceKeyboard());
                return;
            }
            draft.setMaxPrice(maxPrice);
            draft.setState(BotState.WAITING_CONFIRMATION);
            editMessage(chatId, messageId, buildSummary(draft), confirmationKeyboard());
            return;
        }

        if ("act:save".equals(callbackData)) {
            saveDraft(chatId, messageId, draft);
        }
    }

    private void handleMessage(Message message) {
        if (message == null || message.getChatId() == null || message.getText() == null) {
            return;
        }

        String chatId = String.valueOf(message.getChatId());
        String text = message.getText().trim();
        if (text.isBlank()) {
            return;
        }

        if ("/start".equalsIgnoreCase(text) || "/menu".equalsIgnoreCase(text)) {
            sessions.remove(chatId);
            sendMessage(chatId, "Pilih menu:", mainMenuKeyboard());
            return;
        }
        if ("/delete".equalsIgnoreCase(text)) {
            if (subscriptionPassword == null || subscriptionPassword.isBlank()) {
                sendMessage(chatId, "Delete dinonaktifkan: server belum set kai.subscription.password.", null);
                return;
            }

            List<TicketSubscription> subscriptions = subscriptionService.findAllByChatId(chatId);
            if (subscriptions.isEmpty()) {
                sendMessage(chatId, "Tidak ada subscription untuk chat ini.", mainMenuKeyboard());
                return;
            }

            TicketSubscription target = subscriptions.getFirst();
            SubscriptionDraft draft = new SubscriptionDraft();
            draft.setState(BotState.WAITING_DELETE_PASSWORD);
            draft.setPasswordVerified(false);
            draft.setPendingDeleteSubscriptionId(target.getId());
            draft.setPendingDeleteSummary(buildSubscriptionSummary(target));
            sessions.put(chatId, draft);
            sendMessage(chatId, "Masukkan password untuk menghapus subscription ini:\n\n" + draft.getPendingDeleteSummary(), null);
            return;
        }
        if ("/subscriptions".equalsIgnoreCase(text) || "/subs".equalsIgnoreCase(text) || "/list".equalsIgnoreCase(text)) {
            List<TicketSubscription> subscriptions = subscriptionService.findAllByChatId(chatId);
            if (subscriptions.isEmpty()) {
                sendMessage(chatId, "Tidak ada subscription untuk chat ini.", mainMenuKeyboard());
                return;
            }
            StringBuilder out = new StringBuilder();
            out.append("Daftar subscription (chat_id=").append(chatId).append("):\n\n");
            for (TicketSubscription subscription : subscriptions) {
                out.append("ID: ").append(subscription.getId()).append("\n")
                        .append("Tanggal: ").append(subscription.getStartDate()).append(" s/d ").append(subscription.getEndDate()).append("\n")
                        .append("Rute: ").append(subscription.getOrigination()).append(" -> ").append(subscription.getDestination()).append("\n")
                        .append("Max price: ").append(subscription.getMaxPrice()).append("\n\n");
            }
            sendMessage(chatId, out.toString().trim(), mainMenuKeyboard());
            return;
        }
        if ("/new".equalsIgnoreCase(text)) {
            SubscriptionDraft draft = new SubscriptionDraft();
            if (subscriptionPassword != null && !subscriptionPassword.isBlank()) {
                draft.setState(BotState.WAITING_PASSWORD);
                draft.setPasswordVerified(false);
                sessions.put(chatId, draft);
                sendMessage(chatId, "Masukkan password untuk membuat subscription:", null);
                return;
            }

            draft.setPasswordVerified(true);
            draft.setState(BotState.WAITING_START_MONTH);
            sessions.put(chatId, draft);
            sendMessage(chatId, "Pilih bulan mulai (tahun otomatis):", monthKeyboard());
            return;
        }
        if ("/cancel".equalsIgnoreCase(text)) {
            sessions.remove(chatId);
            sendMessage(chatId, "Flow dibatalkan.", mainMenuKeyboard());
            return;
        }

        SubscriptionDraft draft = sessions.get(chatId);
        if (draft == null) {
            sendMessage(chatId, "Ketik /new untuk buat subscription baru.", mainMenuKeyboard());
            return;
        }

        if (draft.getState() == BotState.WAITING_DELETE_PASSWORD) {
            if (subscriptionPassword != null && !subscriptionPassword.isBlank() && subscriptionPassword.equals(text)) {
                Long subscriptionId = draft.getPendingDeleteSubscriptionId();
                boolean deleted = subscriptionId != null && subscriptionId > 0
                        ? subscriptionService.deleteForChatId(subscriptionId, chatId)
                        : subscriptionService.deleteForChatId(chatId);
                sessions.remove(chatId);
                if (!deleted) {
                    sendMessage(chatId, "Gagal delete: subscription tidak ditemukan untuk chat ini.", mainMenuKeyboard());
                    return;
                }
                String deletedSummary = draft.getPendingDeleteSummary();
                if (deletedSummary == null || deletedSummary.isBlank()) {
                    deletedSummary = "Subscription terhapus.";
                }
                sendMessage(chatId, "Subscription terhapus:\n\n" + deletedSummary, mainMenuKeyboard());
                return;
            }
            sendMessage(chatId, "Password salah. Coba lagi atau /cancel.", null);
            return;
        }

        if (draft.getState() == BotState.WAITING_PASSWORD) {
            if (subscriptionPassword != null && !subscriptionPassword.isBlank() && subscriptionPassword.equals(text)) {
                draft.setPasswordVerified(true);
                draft.setState(BotState.WAITING_START_MONTH);
                sendMessage(chatId, "Password OK. Pilih bulan mulai (tahun otomatis):", monthKeyboard());
                return;
            }
            sendMessage(chatId, "Password salah. Coba lagi atau /cancel.", null);
            return;
        }

        if (draft.getState() == BotState.WAITING_ORIGINATION_TEXT) {
            String station = normalizeStation(text);
            if (station == null) {
                sendMessage(chatId, "Kode origination tidak valid.", null);
                return;
            }
            StationService.Station resolved = stationService.findByCode(station).orElse(null);
            if (resolved == null) {
                sendMessage(chatId, "Kode origination tidak ditemukan di KAI (api/stations2).", null);
                return;
            }
            draft.setOrigination(resolved.code());
            draft.setState(BotState.WAITING_DESTINATION);
            sendMessage(chatId, "Pilih destination:", stationKeyboard("dst"));
            return;
        }

        if (draft.getState() == BotState.WAITING_DESTINATION_TEXT) {
            String station = normalizeStation(text);
            if (station == null) {
                sendMessage(chatId, "Kode destination tidak valid.", null);
                return;
            }
            StationService.Station resolved = stationService.findByCode(station).orElse(null);
            if (resolved == null) {
                sendMessage(chatId, "Kode destination tidak ditemukan di KAI (api/stations2).", null);
                return;
            }
            draft.setDestination(resolved.code());
            draft.setState(BotState.WAITING_MAX_PRICE);
            sendMessage(chatId, "Pilih max price (atau ketik angka):", maxPriceKeyboard());
            return;
        }

        if (draft.getState() == BotState.WAITING_MAX_PRICE && DIGITS_ONLY.matcher(text).matches()) {
            int value = parseIntSafely(text);
            if (value > 0) {
                draft.setMaxPrice(value);
                draft.setState(BotState.WAITING_CONFIRMATION);
                sendMessage(chatId, buildSummary(draft), confirmationKeyboard());
                return;
            }
        }

        sendMessage(chatId, "Input tidak sesuai state saat ini. Gunakan tombol inline atau /cancel.", null);
    }

    private void saveDraft(String chatId, Integer messageId, SubscriptionDraft draft) {
        try {
            if (subscriptionPassword != null && !subscriptionPassword.isBlank() && !draft.isPasswordVerified()) {
                editMessage(chatId, messageId, "Password belum diverifikasi. Ketik /new untuk mulai lagi.", mainMenuKeyboard());
                return;
            }
            SubscriptionRequest request = new SubscriptionRequest(
                    draft.getStartDate(),
                    draft.getEndDate(),
                    draft.getOrigination(),
                    draft.getDestination(),
                    draft.getMaxPrice(),
                    chatId,
                    subscriptionPassword
            );
            SubscriptionResponse response = subscriptionService.createOrAttach(request);
            sessions.remove(chatId);
            editMessage(
                    chatId,
                    messageId,
                    "Subscription tersimpan.\n"
                            + "ID: " + response.id() + "\n"
                            + "Rute: " + response.origination() + " -> " + response.destination() + "\n"
                            + "Rentang tanggal: " + response.startDate() + " s/d " + response.endDate() + "\n"
                            + "Max price: " + response.maxPrice(),
                    mainMenuKeyboard()
            );
        } catch (IllegalArgumentException e) {
            editMessage(chatId, messageId, "Gagal simpan: " + e.getMessage(), confirmationKeyboard());
        } catch (Exception e) {
            LOG.error("Gagal simpan subscription dari Telegram bot", e);
            editMessage(chatId, messageId, "Terjadi error saat simpan subscription.", confirmationKeyboard());
        }
    }

    private String buildSummary(SubscriptionDraft draft) {
        return "Konfirmasi subscription:\n"
                + "start_date: " + draft.getStartDate() + "\n"
                + "end_date: " + draft.getEndDate() + "\n"
                + "origination: " + draft.getOrigination() + "\n"
                + "destination: " + draft.getDestination() + "\n"
                + "max_price: " + draft.getMaxPrice();
    }

    private String buildSubscriptionSummary(TicketSubscription subscription) {
        return "ID: " + subscription.getId() + "\n"
                + "Tanggal: " + subscription.getStartDate() + " s/d " + subscription.getEndDate() + "\n"
                + "Rute: " + subscription.getOrigination() + " -> " + subscription.getDestination() + "\n"
                + "Max price: " + subscription.getMaxPrice();
    }

    private InlineKeyboardMarkup mainMenuKeyboard() {
        return keyboard(List.of(
                List.of(button("Buat Subscription", "menu:new"))
        ));
    }

    private InlineKeyboardMarkup monthKeyboard() {
        int currentMonth = LocalDate.now().getMonthValue();
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int month = currentMonth; month <= 12; month++) {
            String label = Month.of(month).getDisplayName(TextStyle.SHORT, ID_LOCALE);
            row.add(button(label, "sm:" + month));
            if (row.size() == 3) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(List.of(button("Batal", "act:cancel")));
        return keyboard(rows);
    }

    private InlineKeyboardMarkup startDateKeyboardForMonth(int month) {
        int year = LocalDate.now().getYear();
        YearMonth targetMonth = YearMonth.of(year, month);
        int daysInMonth = targetMonth.lengthOfMonth();
        int fromDay = month == LocalDate.now().getMonthValue() ? LocalDate.now().getDayOfMonth() : 1;

        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> currentRow = new ArrayList<>();
        for (int day = fromDay; day <= daysInMonth; day++) {
            LocalDate date = LocalDate.of(year, month, day);
            currentRow.add(button(String.valueOf(day), "sd:" + date));
            if (currentRow.size() == 7) {
                rows.add(currentRow);
                currentRow = new ArrayList<>();
            }
        }
        if (!currentRow.isEmpty()) {
            rows.add(currentRow);
        }
        rows.add(List.of(button("Batal", "act:cancel")));
        return keyboard(rows);
    }

    private InlineKeyboardMarkup endDateKeyboard(LocalDate startDate) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        int year = LocalDate.now().getYear();

        for (int i = 0; i <= 5; i++) {
            LocalDate date = startDate.plusDays(i);
            if (date.getYear() != year) {
                break;
            }
            row.add(button(DISPLAY_DATE.format(date), "ed:" + date));
            if (row.size() == 3) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        if (!row.isEmpty()) {
            rows.add(row);
        }
        rows.add(List.of(button("Batal", "act:cancel")));
        return keyboard(rows);
    }

    private InlineKeyboardMarkup stationKeyboard(String prefix) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();
        List<InlineKeyboardButton> row = new ArrayList<>();
        for (int i = 0; i < COMMON_STATIONS.size(); i++) {
            String code = COMMON_STATIONS.get(i);
            row.add(button(code, prefix + ":" + code));
            if (row.size() == 3 || i == COMMON_STATIONS.size() - 1) {
                rows.add(row);
                row = new ArrayList<>();
            }
        }
        rows.add(List.of(button("Input Manual", prefix + ":manual")));
        rows.add(List.of(button("Batal", "act:cancel")));
        return keyboard(rows);
    }

    private InlineKeyboardMarkup maxPriceKeyboard() {
        return keyboard(List.of(
                List.of(button("200000", "mp:200000"), button("300000", "mp:300000")),
                List.of(button("500000", "mp:500000"), button("750000", "mp:750000")),
                List.of(button("1000000", "mp:1000000")),
                List.of(button("Input Manual", "mp:manual")),
                List.of(button("Batal", "act:cancel"))
        ));
    }

    private InlineKeyboardMarkup confirmationKeyboard() {
        return keyboard(List.of(
                List.of(button("Simpan", "act:save")),
                List.of(button("Batal", "act:cancel"))
        ));
    }

    private InlineKeyboardMarkup keyboard(List<List<InlineKeyboardButton>> rows) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        InlineKeyboardButton button = new InlineKeyboardButton();
        button.setText(text);
        button.setCallbackData(callbackData);
        return button;
    }

    private void sendMessage(String chatId, String text, InlineKeyboardMarkup markup) {
        if (botToken.isBlank()) {
            LOG.warn("Telegram bot token belum diatur. Set kai.telegram.bot-token.");
            return;
        }
        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(chatId);
        sendMessage.setText(text);
        if (markup != null) {
            sendMessage.setReplyMarkup(markup);
        }
        try {
            sender.execute(sendMessage);
        } catch (TelegramApiException e) {
            LOG.errorf(e, "Gagal kirim message ke chat_id=%s", chatId);
        }
    }

    private void editMessage(String chatId, Integer messageId, String text, InlineKeyboardMarkup markup) {
        if (botToken.isBlank()) {
            LOG.warn("Telegram bot token belum diatur. Set kai.telegram.bot-token.");
            return;
        }
        if (messageId == null || messageId <= 0) {
            sendMessage(chatId, text, markup);
            return;
        }
        EditMessageText editMessageText = new EditMessageText();
        editMessageText.setChatId(chatId);
        editMessageText.setMessageId(messageId);
        editMessageText.setText(text);
        if (markup != null) {
            editMessageText.setReplyMarkup(markup);
        }
        try {
            sender.execute(editMessageText);
        } catch (TelegramApiException e) {
            LOG.errorf(e, "Gagal edit message chat_id=%s message_id=%d", chatId, messageId);
            sendMessage(chatId, text, markup);
        }
    }

    private void answerCallback(String callbackId) {
        if (callbackId == null || callbackId.isBlank() || botToken.isBlank()) {
            return;
        }
        AnswerCallbackQuery answer = new AnswerCallbackQuery();
        answer.setCallbackQueryId(callbackId);
        try {
            sender.execute(answer);
        } catch (TelegramApiException e) {
            LOG.warnf(e, "Gagal answer callback_id=%s", callbackId);
        }
    }

    private String normalizeStation(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        if (normalized.isBlank() || normalized.length() > 10) {
            return null;
        }
        return normalized;
    }

    private int parseIntSafely(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return -1;
        }
    }

    private LocalDate parseLocalDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static class TelegramSender extends DefaultAbsSender {
        private final String botToken;

        TelegramSender(String botToken) {
            super(new DefaultBotOptions());
            this.botToken = botToken == null ? "" : botToken;
        }

        @Override
        public String getBotToken() {
            return botToken;
        }
    }
}
