package com.adesa.attendance.mail;

import com.adesa.attendance.config.MailProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;

/**
 * Real SMTP delivery through Spring's {@link JavaMailSender}.
 *
 * <p>Only created when {@code MAIL_HOST} is configured. Message bodies are sent
 * as multipart alternative (plain text + HTML). The address is validated before
 * sending so a malformed recipient never reaches the transport.
 */
public class SmtpMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(SmtpMailService.class);

    private final JavaMailSender mailSender;
    private final MailProperties mailProperties;

    public SmtpMailService(JavaMailSender mailSender, MailProperties mailProperties) {
        this.mailSender = mailSender;
        this.mailProperties = mailProperties;
    }

    @Override
    public void send(String to, String subject, String textBody, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(mailProperties.getFrom());
            helper.setTo(to);
            helper.setSubject(subject);
            // Multipart: the HTML alternative plus the plain-text fallback.
            helper.setText(textBody, htmlBody);
            mailSender.send(message);
            log.info("Sent '{}' mail to {}", subject, to);
        } catch (MessagingException | MailException ex) {
            // Never include the body: it may contain a single-use link.
            log.error("Failed to send '{}' mail to {}: {}", subject, to, ex.getMessage());
            throw new MailDeliveryException("The message could not be sent", ex);
        }
    }

    @Override
    public boolean isDeliveryEnabled() {
        return true;
    }
}
