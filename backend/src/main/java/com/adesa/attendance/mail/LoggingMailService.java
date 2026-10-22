package com.adesa.attendance.mail;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Development/test mail service used when no SMTP host is configured.
 *
 * <p>It never touches the network. Instead it records the messages it would
 * have sent so a developer can read the setup/recovery link from the log
 * without a working mailbox, and tests can assert on delivered messages.
 *
 * <p>The recorded link is intentionally exposed only in the log at INFO level;
 * it is never returned in an API response.
 */
public class LoggingMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(LoggingMailService.class);

    private final List<SentMessage> sent = new CopyOnWriteArrayList<>();

    /** A message that would have been sent. Visible for tests and diagnostics. */
    public record SentMessage(String to, String subject, String textBody, String htmlBody) {
    }

    @Override
    public void send(String to, String subject, String textBody, String htmlBody) {
        sent.add(new SentMessage(to, subject, textBody, htmlBody));
        log.info("Mail delivery is disabled (no MAIL_HOST configured). "
                + "Would send '{}' to {}. Body not logged beyond the link below at INFO:", subject, to);
        // The plain-text body contains the single-use link, which in development
        // is the only way to complete the flow. This is a developer machine.
        log.info("--- development mail ---\nTo: {}\nSubject: {}\n{}\n------------------------", to, subject, textBody);
    }

    @Override
    public boolean isDeliveryEnabled() {
        return false;
    }

    /** The messages recorded so far, oldest first. */
    public List<SentMessage> sentMessages() {
        return Collections.unmodifiableList(new ArrayList<>(sent));
    }

    /** Clears the recorded messages. */
    public void clear() {
        sent.clear();
    }
}
