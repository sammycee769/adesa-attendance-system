package com.adesa.attendance.assets;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "adesa.assets.mail.enabled", havingValue = "true", matchIfMissing = true)
class AssetMailScheduling {
    private final AssetMailDispatcher dispatcher;

    AssetMailScheduling(AssetMailDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Scheduled(fixedDelayString = "${adesa.assets.mail.poll-ms:5000}", initialDelay = 5000)
    public void deliverPendingMail() {
        dispatcher.deliver();
    }
}
