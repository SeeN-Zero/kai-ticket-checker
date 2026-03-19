package seen.kai.telegram.state;

import lombok.Data;

import java.time.LocalDate;
import java.util.List;

@Data
public class SubscriptionDraft {
    private BotState state = BotState.IDLE;
    private boolean passwordVerified;
    private Long pendingDeleteSubscriptionId;
    private String pendingDeleteSummary;
    private Integer startMonth;
    private LocalDate startDate;
    private LocalDate endDate;
    private List<String> departureCity;
    private String origination;
    private List<String> arrivalCity;
    private String destination;
    private Integer maxPrice;
}
