package com.orpex.sending;

import com.orpex.mail.SmtpHealthService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** Déclenche le worker d'envoi à intervalle régulier ; désactivable ({@code orpex.worker.enabled=false}, tests). */
@Configuration
@EnableScheduling
@ConditionalOnBooleanProperty(name = "orpex.worker.enabled", matchIfMissing = true)
class SendScheduler {

    private final SendWorker worker;
    private final SmtpHealthService health;

    SendScheduler(SendWorker worker, SmtpHealthService health) {
        this.worker = worker;
        this.health = health;
    }

    /** Contrôle de santé du relais (spécification §10) : connexion sans envoi, résultat affiché à l'administrateur. */
    @Scheduled(fixedDelayString = "${orpex.worker.smtp-health-interval}", initialDelayString = "PT1M")
    void checkRelay() {
        health.checkHealth();
    }

    /** Au démarrage : les envois interrompus par l'arrêt précédent passent « à vérifier » sans attendre. */
    @EventListener(ApplicationReadyEvent.class)
    void recoverAtStartup() {
        worker.recoverInterruptedSends();
    }

    @Scheduled(fixedDelayString = "${orpex.worker.delay}", initialDelayString = "${orpex.worker.delay}")
    void run() {
        worker.processDueSends();
    }
}
