package seen.kai.subscription.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;

public record SubscriptionRequest(
        @JsonProperty("start_date") LocalDate startDate,
        @JsonProperty("end_date") LocalDate endDate,
        String origination,
        String destination,
        @JsonProperty("max_price") Integer maxPrice,
        @JsonProperty("telegram_chat_id") String telegramChatId,
        String password
) {
    public String resolveChatId() {
        if (telegramChatId == null || telegramChatId.isBlank()) {
            return null;
        }
        return telegramChatId.trim();
    }
}
