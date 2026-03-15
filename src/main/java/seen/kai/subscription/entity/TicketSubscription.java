package seen.kai.subscription.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
        name = "ticket_subscriptions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_ticket_subscription_route",
                columnNames = {"start_date", "end_date", "origination", "destination", "max_price"}
        )
)
public class TicketSubscription {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "origination", nullable = false, length = 20)
    private String origination;

    @Column(name = "origination_name", length = 200)
    private String originationName;

    @Column(name = "destination", nullable = false, length = 20)
    private String destination;

    @Column(name = "destination_name", length = 200)
    private String destinationName;

    @Column(name = "max_price", nullable = false)
    private int maxPrice;

    @ManyToMany
    @JoinTable(
            name = "ticket_subscription_chat",
            joinColumns = @JoinColumn(name = "ticket_subscription_id"),
            inverseJoinColumns = @JoinColumn(name = "telegram_chat_id"),
            uniqueConstraints = @UniqueConstraint(
                    name = "uk_ticket_subscription_chat_telegram_chat_id",
                    columnNames = "telegram_chat_id"
            )
    )
    private final Set<TelegramChat> telegramChats = new HashSet<>();

    public Long getId() {
        return id;
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

    public String getOriginationName() {
        return originationName;
    }

    public void setOriginationName(String originationName) {
        this.originationName = originationName;
    }

    public String getDestination() {
        return destination;
    }

    public void setDestination(String destination) {
        this.destination = destination;
    }

    public String getDestinationName() {
        return destinationName;
    }

    public void setDestinationName(String destinationName) {
        this.destinationName = destinationName;
    }

    public int getMaxPrice() {
        return maxPrice;
    }

    public void setMaxPrice(int maxPrice) {
        this.maxPrice = maxPrice;
    }

    public Set<TelegramChat> getTelegramChats() {
        return telegramChats;
    }

    public void addTelegramChat(TelegramChat chat) {
        telegramChats.add(chat);
        chat.getSubscriptions().add(this);
    }

    public void removeTelegramChat(TelegramChat chat) {
        if (chat == null) {
            return;
        }
        telegramChats.remove(chat);
        chat.getSubscriptions().remove(this);
    }
}
