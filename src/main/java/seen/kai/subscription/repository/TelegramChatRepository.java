package seen.kai.subscription.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import seen.kai.subscription.entity.TelegramChat;

import java.util.List;

@ApplicationScoped
public class TelegramChatRepository {
    private final EntityManager entityManager;

    public TelegramChatRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public TelegramChat findByChatId(String chatId) {
        List<TelegramChat> result = entityManager.createQuery("""
                        select tc from TelegramChat tc
                        where tc.chatId = :chatId
                        """, TelegramChat.class)
                .setParameter("chatId", chatId)
                .getResultList();
        return result.isEmpty() ? null : result.getFirst();
    }

    public void persist(TelegramChat chat) {
        entityManager.persist(chat);
    }
}
