package seen.kai.subscription.bootstrap;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

@ApplicationScoped
public class SubscriptionDbGuard {
    private static final Logger LOG = Logger.getLogger(SubscriptionDbGuard.class);

    private final EntityManager entityManager;

    public SubscriptionDbGuard(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional
    void onStart(@Observes StartupEvent event) {
        // Enforce "1 chatId -> 1 subscription" at DB level.
        // Postgres-specific, idempotent.
        try {
            entityManager.createNativeQuery("""
                            create unique index if not exists uk_ticket_subscription_chat_telegram_chat_id
                            on ticket_subscription_chat (telegram_chat_id)
                            """)
                    .executeUpdate();
            LOG.info("DB guard installed: unique index uk_ticket_subscription_chat_telegram_chat_id");
        } catch (Exception e) {
            // If existing data violates the constraint, index creation will fail; that's intentional.
            throw new IllegalStateException(
                    "DB guard gagal dibuat. Pastikan tidak ada chat_id yang terhubung ke lebih dari 1 subscription " +
                            "di table ticket_subscription_chat(telegram_chat_id).",
                    e
            );
        }
    }
}

