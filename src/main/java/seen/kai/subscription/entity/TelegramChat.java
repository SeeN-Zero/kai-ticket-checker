package seen.kai.subscription.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Setter
@Table(
        name = "telegram_chats",
        uniqueConstraints = @UniqueConstraint(name = "uk_telegram_chat_id", columnNames = "chat_id")
)
public class TelegramChat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Setter(lombok.AccessLevel.NONE)
    private Long id;

    @Column(name = "chat_id", nullable = false, length = 100)
    private String chatId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_subscription_id")
    private TicketSubscription subscription;
}
