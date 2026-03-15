package seen.kai.subscription.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.persistence.EntityManager;
import seen.kai.subscription.entity.TicketSubscription;

import java.time.LocalDate;
import java.util.List;

@ApplicationScoped
public class TicketSubscriptionRepository {
    private final EntityManager entityManager;

    public TicketSubscriptionRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public TicketSubscription findByUniqueKey(
            LocalDate startDate,
            LocalDate endDate,
            String origination,
            String destination,
            int maxPrice
    ) {
        List<TicketSubscription> result = entityManager.createQuery("""
                        select ts from TicketSubscription ts
                        where ts.startDate = :startDate
                          and ts.endDate = :endDate
                          and ts.origination = :origination
                          and ts.destination = :destination
                          and ts.maxPrice = :maxPrice
                        """, TicketSubscription.class)
                .setParameter("startDate", startDate)
                .setParameter("endDate", endDate)
                .setParameter("origination", origination)
                .setParameter("destination", destination)
                .setParameter("maxPrice", maxPrice)
                .getResultList();
        return result.isEmpty() ? null : result.getFirst();
    }

    public void persist(TicketSubscription subscription) {
        entityManager.persist(subscription);
    }

    public TicketSubscription findByIdWithChats(Long id) {
        if (id == null) {
            return null;
        }
        List<TicketSubscription> result = entityManager.createQuery("""
                        select ts
                        from TicketSubscription ts
                        left join fetch ts.telegramChats chats
                        where ts.id = :id
                        """, TicketSubscription.class)
                .setParameter("id", id)
                .getResultList();
        return result.isEmpty() ? null : result.getFirst();
    }

    public List<TicketSubscription> findAllWithChats() {
        return entityManager.createQuery("""
                        select distinct ts
                        from TicketSubscription ts
                        left join fetch ts.telegramChats chats
                        """, TicketSubscription.class)
                .getResultList();
    }

    public List<TicketSubscription> findAllByChatId(String chatId) {
        return entityManager.createQuery("""
                        select distinct ts
                        from TicketSubscription ts
                        join ts.telegramChats chats
                        where chats.chatId = :chatId
                        order by ts.id desc
                        """, TicketSubscription.class)
                .setParameter("chatId", chatId)
                .getResultList();
    }

    public TicketSubscription findFirstByChatIdWithChats(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return null;
        }
        List<TicketSubscription> result = entityManager.createQuery("""
                        select distinct ts
                        from TicketSubscription ts
                        join fetch ts.telegramChats chats
                        where chats.chatId = :chatId
                        order by ts.id desc
                        """, TicketSubscription.class)
                .setParameter("chatId", chatId)
                .setMaxResults(1)
                .getResultList();
        return result.isEmpty() ? null : result.getFirst();
    }

    public void delete(TicketSubscription subscription) {
        if (subscription == null) {
            return;
        }
        entityManager.remove(entityManager.contains(subscription) ? subscription : entityManager.merge(subscription));
    }

    public void flush() {
        entityManager.flush();
    }
}
