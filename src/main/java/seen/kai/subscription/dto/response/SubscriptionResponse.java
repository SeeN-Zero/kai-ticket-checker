package seen.kai.subscription.dto.response;

import com.fasterxml.jackson.annotation.JsonProperty;
import seen.kai.subscription.entity.TelegramChat;
import seen.kai.subscription.entity.TicketSubscription;

import java.time.LocalDate;
import java.util.List;

public record SubscriptionResponse(
        Long id,
        @JsonProperty("start_date") LocalDate startDate,
        @JsonProperty("end_date") LocalDate endDate,
        String origination,
        String destination,
        @JsonProperty("max_price") int maxPrice,
        @JsonProperty("telegram_chat_ids") List<String> telegramChatIds
) {
    public static SubscriptionResponse from(TicketSubscription subscription) {
        List<String> chatIds = subscription.getTelegramChats().stream()
                .map(TelegramChat::getChatId)
                .sorted()
                .toList();

        return new SubscriptionResponse(
                subscription.getId(),
                subscription.getStartDate(),
                subscription.getEndDate(),
                subscription.getOrigination(),
                subscription.getDestination(),
                subscription.getMaxPrice(),
                chatIds
        );
    }
}
