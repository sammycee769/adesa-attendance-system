package com.adesa.attendance.payments;
import java.time.Instant;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
@Configuration
@ConditionalOnProperty(name="adesa.payments.scheduler-enabled",havingValue="true",matchIfMissing=true)
@EnableScheduling
class PaymentScheduling {
 private final PaymentDispatcher dispatcher;
 PaymentScheduling(PaymentDispatcher dispatcher){this.dispatcher=dispatcher;}
 @Scheduled(fixedDelayString="${adesa.payments.poll-ms:60000}",initialDelayString="${adesa.payments.initial-delay-ms:60000}")
 public void run(){dispatcher.tick(Instant.now());}
}
