package seen.kai.subscription.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.util.List;

public record SubscriptionRequest(
        @JsonProperty("start_date") LocalDate startDate,
        @JsonProperty("end_date") LocalDate endDate,
        String origination,
        String destination,
        @JsonProperty("max_price") Integer maxPrice,
        @JsonProperty("telegram_chat_id") String telegramChatId,
        String password
) {
    public List<String> resolveChatIds() {
        if (telegramChatId == null || telegramChatId.isBlank()) {
            return List.of();
        }
        return List.of(telegramChatId.trim());
    }
}
