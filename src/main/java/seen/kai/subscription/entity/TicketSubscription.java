package seen.kai.subscription.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
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
    @Setter(lombok.AccessLevel.NONE)
    private Long id;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
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

    @OneToMany(mappedBy = "subscription", cascade = CascadeType.ALL, orphanRemoval = false)
    @Setter(lombok.AccessLevel.NONE)
    private final Set<TelegramChat> telegramChats = new HashSet<>();

    public void addTelegramChat(TelegramChat chat) {
        if (chat == null) {
            return;
        }
        telegramChats.add(chat);
        chat.setSubscription(this);
    }

    public void removeTelegramChat(TelegramChat chat) {
        if (chat == null) {
            return;
        }
        telegramChats.remove(chat);
        if (chat.getSubscription() == this) {
            chat.setSubscription(null);
        }
    }
}
