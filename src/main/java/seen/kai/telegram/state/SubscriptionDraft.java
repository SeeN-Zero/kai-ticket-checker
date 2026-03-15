package seen.kai.telegram.state;

import java.time.LocalDate;

public class SubscriptionDraft {
    private BotState state = BotState.IDLE;
    private boolean passwordVerified;
    private Long pendingDeleteSubscriptionId;
    private String pendingDeleteSummary;
    private Integer startMonth;
    private LocalDate startDate;
    private LocalDate endDate;
    private String origination;
    private String destination;
    private Integer maxPrice;

    public BotState getState() {
        return state;
    }

    public void setState(BotState state) {
        this.state = state;
    }

    public boolean isPasswordVerified() {
        return passwordVerified;
    }

    public void setPasswordVerified(boolean passwordVerified) {
        this.passwordVerified = passwordVerified;
    }

    public Long getPendingDeleteSubscriptionId() {
        return pendingDeleteSubscriptionId;
    }

    public void setPendingDeleteSubscriptionId(Long pendingDeleteSubscriptionId) {
        this.pendingDeleteSubscriptionId = pendingDeleteSubscriptionId;
    }

    public String getPendingDeleteSummary() {
        return pendingDeleteSummary;
    }

    public void setPendingDeleteSummary(String pendingDeleteSummary) {
        this.pendingDeleteSummary = pendingDeleteSummary;
    }

    public Integer getStartMonth() {
        return startMonth;
    }

    public void setStartMonth(Integer startMonth) {
        this.startMonth = startMonth;
    }

    public LocalDate getStartDate() {
        return startDate;
    }

    public void setStartDate(LocalDate startDate) {
        this.startDate = startDate;
    }

    public LocalDate getEndDate() {
        return endDate;
    }

    public void setEndDate(LocalDate endDate) {
        this.endDate = endDate;
    }

    public String getOrigination() {
        return origination;
    }

    public void setOrigination(String origination) {
        this.origination = origination;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public Integer getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(Integer maxPrice) {
        this.maxPrice = maxPrice;
    }
}
