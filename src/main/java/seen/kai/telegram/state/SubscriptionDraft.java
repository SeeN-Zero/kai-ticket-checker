package seen.kai.telegram.state;

import lombok.Data;
import seen.kai.checker.service.StationService;

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
    private List<StationService.Station> departureStations;
    private String origination;
    private List<StationService.Station> arrivalStations;
    private String destination;
    private Integer maxPrice;
}
