package seen.kai.subscription.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.transaction.Transactional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import seen.kai.checker.service.StationService;
import seen.kai.subscription.dto.request.SubscriptionRequest;
import seen.kai.subscription.dto.response.SubscriptionResponse;
import seen.kai.subscription.entity.TelegramChat;
import seen.kai.subscription.entity.TicketSubscription;
import seen.kai.subscription.repository.TelegramChatRepository;
import seen.kai.subscription.repository.TicketSubscriptionRepository;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@ApplicationScoped
public class SubscriptionService {
    private final TicketSubscriptionRepository ticketSubscriptionRepository;
    private final TelegramChatRepository telegramChatRepository;
    private final StationService stationService;

    @ConfigProperty(name = "kai.subscription.password", defaultValue = "")
    String subscriptionPassword;

    public SubscriptionService(
            TicketSubscriptionRepository ticketSubscriptionRepository,
            TelegramChatRepository telegramChatRepository,
            StationService stationService
    ) {
        this.ticketSubscriptionRepository = ticketSubscriptionRepository;
        this.telegramChatRepository = telegramChatRepository;
        this.stationService = stationService;
    }

    @Transactional
    public SubscriptionResponse createOrAttach(SubscriptionRequest request) {
        validate(request);

        StationService.Station originationStation = stationService.requireByName(request.origination());
        StationService.Station destinationStation = stationService.requireByName(request.destination());

        String normalizedOrigination = originationStation.code().trim().toUpperCase(Locale.ROOT);
        String normalizedDestination = destinationStation.code().trim().toUpperCase(Locale.ROOT);
        String chatId = request.resolveChatId();

        TicketSubscription subscription = ticketSubscriptionRepository.findByUniqueKey(
                request.startDate(),
                request.endDate(),
                normalizedOrigination,
                normalizedDestination,
                request.maxPrice()
        );

        if (subscription == null) {
            subscription = new TicketSubscription();
            subscription.setStartDate(request.startDate());
            subscription.setEndDate(request.endDate());
            subscription.setOrigination(normalizedOrigination);
            subscription.setOriginationName(originationStation.name());
            subscription.setDestination(normalizedDestination);
            subscription.setDestinationName(destinationStation.name());
            subscription.setMaxPrice(request.maxPrice());
            ticketSubscriptionRepository.persist(subscription);
        } else {
            // Keep station names fresh if they were previously null/old.
            subscription.setOriginationName(originationStation.name());
            subscription.setDestinationName(destinationStation.name());
        }

        TelegramChat chat = telegramChatRepository.findByChatId(chatId);
        if (chat == null) {
            chat = new TelegramChat();
            chat.setChatId(chatId);
            telegramChatRepository.persist(chat);
        }

        TicketSubscription existing = chat.getSubscription();
        if (existing != null && existing != subscription) {
            if (existing.getId() != null) {
                throw new IllegalArgumentException(
                        "Chat ini sudah punya subscription id=" + existing.getId()
                                + ". Hapus dulu pakai /delete lalu masukkan password."
                );
            }
            throw new IllegalArgumentException("Chat ini sudah punya subscription. Hapus dulu sebelum membuat yang baru.");
        }
        subscription.addTelegramChat(chat);

        ticketSubscriptionRepository.flush();
        return SubscriptionResponse.from(subscription);
    }

    @Transactional
    public List<TicketSubscription> findAllWithChats() {
        return ticketSubscriptionRepository.findAllWithChats();
    }

    @Transactional
    public List<TicketSubscription> findAllByChatId(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return List.of();
        }
        return ticketSubscriptionRepository.findAllByChatId(chatId.trim());
    }

    @Transactional
    public boolean deleteForChatId(Long subscriptionId, String chatId) {
        if (subscriptionId == null || subscriptionId <= 0 || chatId == null || chatId.isBlank()) {
            return false;
        }

        TicketSubscription subscription = ticketSubscriptionRepository.findByIdWithChats(subscriptionId);
        if (subscription == null) {
            return false;
        }

        TelegramChat chat = subscription.getTelegramChats().stream()
                .filter(value -> value != null && chatId.trim().equals(value.getChatId()))
                .findFirst()
                .orElse(null);
        if (chat == null) {
            return false;
        }

        subscription.removeTelegramChat(chat);
        if (subscription.getTelegramChats().isEmpty()) {
            ticketSubscriptionRepository.delete(subscription);
        }
        ticketSubscriptionRepository.flush();
        return true;
    }

    @Transactional
    public boolean deleteForChatId(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return false;
        }
        TicketSubscription subscription = ticketSubscriptionRepository.findFirstByChatIdWithChats(chatId.trim());
        if (subscription == null) {
            return false;
        }
        return deleteForChatId(subscription.getId(), chatId);
    }

    private void validate(SubscriptionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Request body wajib diisi.");
        }
        if (subscriptionPassword != null && !subscriptionPassword.isBlank()) {
            if (request.password() == null || request.password().isBlank()) {
                throw new IllegalArgumentException("password wajib diisi.");
            }
            if (!Objects.equals(subscriptionPassword, request.password())) {
                throw new IllegalArgumentException("password salah.");
            }
        }
        if (request.startDate() == null) {
            throw new IllegalArgumentException("start_date wajib diisi.");
        }
        if (request.endDate() == null) {
            throw new IllegalArgumentException("end_date wajib diisi.");
        }
        if (request.endDate().isBefore(request.startDate())) {
            throw new IllegalArgumentException("end_date tidak boleh lebih kecil dari start_date.");
        }
        long rangeDays = ChronoUnit.DAYS.between(request.startDate(), request.endDate());
        if (rangeDays > 5) {
            throw new IllegalArgumentException("Rentang tanggal maksimal 5 hari.");
        }
        int currentYear = LocalDate.now().getYear();
        if (request.startDate().getYear() != currentYear || request.endDate().getYear() != currentYear) {
            throw new IllegalArgumentException("Tahun harus tahun sekarang (" + currentYear + ").");
        }
        if (request.origination() == null || request.origination().isBlank()) {
            throw new IllegalArgumentException("origination wajib diisi.");
        }
        if (request.destination() == null || request.destination().isBlank()) {
            throw new IllegalArgumentException("destination wajib diisi.");
        }
        if (request.origination().trim().equalsIgnoreCase(request.destination().trim())) {
            throw new IllegalArgumentException("origination dan destination tidak boleh sama.");
        }
        if (request.maxPrice() == null || request.maxPrice() <= 0) {
            throw new IllegalArgumentException("max_price wajib > 0.");
        }
        if (request.resolveChatId() == null) {
            throw new IllegalArgumentException("telegram_chat_id wajib diisi.");
        }
    }
}
