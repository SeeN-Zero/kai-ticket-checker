package seen.kai.subscription.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.util.HashSet;
import java.util.Set;

@Entity
@Table(
        name = "telegram_chats",
        uniqueConstraints = @UniqueConstraint(name = "uk_telegram_chat_id", columnNames = "chat_id")
)
public class TelegramChat {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false, length = 100)
    private String chatId;

    @ManyToMany(mappedBy = "telegramChats")
    private final Set<TicketSubscription> subscriptions = new HashSet<>();

    public Long getId() {
        return id;
    }

    public String getChatId() {
        return chatId;
    }

    public void setChatId(String chatId) {
        this.chatId = chatId;
    }

    public Set<TicketSubscription> getSubscriptions() {
        return subscriptions;
    }
}
