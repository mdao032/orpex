package com.orpex.sending;

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

    SendScheduler(SendWorker worker) {
        this.worker = worker;
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
