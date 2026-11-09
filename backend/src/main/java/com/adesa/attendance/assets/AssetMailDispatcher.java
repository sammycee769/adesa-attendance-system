package com.adesa.attendance.assets;

import com.adesa.attendance.mail.MailService;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

@Service
class AssetMailDispatcher {
    private static final Logger log = LoggerFactory.getLogger(AssetMailDispatcher.class);
    private final EntityManager em;
    private final MailService mail;

    AssetMailDispatcher(EntityManager em, MailService mail) {
        this.em = em;
        this.mail = mail;
    }

    @Transactional
    @SuppressWarnings("unchecked")
    public void deliver() {
        List<AssetMail> pending = em.createNativeQuery("select * from asset_mail_outbox "
                + "where status in ('PENDING','RETRY') and next_attempt_at<=now() "
                + "order by created_at limit 10 for update skip locked", AssetMail.class).getResultList();
        for (AssetMail message : pending) {
            message.attempts++;
            try {
                String html = "<html><body><p>" + HtmlUtils.htmlEscape(message.textBody)
                        .replace("\n", "<br>") + "</p></body></html>";
                mail.send(message.recipientEmail, message.subject, message.textBody, html);
                message.status = mail.isDeliveryEnabled() ? "SENT" : "LOCAL_CAPTURED";
                message.deliveredAt = Instant.now();
            } catch (RuntimeException ex) {
                message.status = message.attempts >= 8 ? "FAILED" : "RETRY";
                message.nextAttemptAt = Instant.now().plusSeconds(Math.min(3600, 30L << Math.min(message.attempts, 7)));
                log.warn("Asset email {} delivery failed; status={}, attempt={}", message.id, message.status, message.attempts);
            }
        }
    }
}
