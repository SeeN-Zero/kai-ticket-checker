package seen.kai.checker.scheduler;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import seen.kai.checker.service.KaiService;

import java.util.concurrent.atomic.AtomicBoolean;

@ApplicationScoped
public class KaiScheduler {
    private static final Logger LOG = Logger.getLogger(KaiScheduler.class);
    private final AtomicBoolean running = new AtomicBoolean(false);

    @Inject
    KaiService kaiService;

    @ConfigProperty(name = "kai.scheduler.enabled", defaultValue = "true")
    boolean schedulerEnabled;

//    @Scheduled(every = "{kai.scheduler.every:30m}", delay = 0)
    void check() {
        if (!schedulerEnabled) {
            LOG.info("Scheduler dinonaktifkan via konfigurasi (kai.scheduler.enabled=false).");
            return;
        }

        if (!running.compareAndSet(false, true)) {
            LOG.info("Skip scheduler run: task sebelumnya masih berjalan.");
            return;
        }

        Thread.startVirtualThread(() -> {
            try {
                LOG.info("Checking ticket from database subscriptions...");
                kaiService.checkTicketFromDatabase("");
            } finally {
                running.set(false);
            }
        });
    }
}
