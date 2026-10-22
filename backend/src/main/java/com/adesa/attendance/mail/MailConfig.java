package com.adesa.attendance.mail;

import java.util.Properties;

import com.adesa.attendance.config.MailProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Chooses the mail transport at startup.
 *
 * <p>If {@code MAIL_HOST} and the sender address are set, a real
 * {@link JavaMailSenderImpl} is built. Otherwise a {@link LoggingMailService}
 * is used, which never opens a network connection — this is what makes it
 * impossible for a development or test run to send a production message by
 * accident.
 *
 * <p>Because the mail settings are read from {@code adesa.mail} (bound to
 * {@code MAIL_*} environment variables, see {@code application.yml}) the
 * credentials never appear in source control.
 */
@Configuration
public class MailConfig {

    private static final Logger log = LoggerFactory.getLogger(MailConfig.class);

    @Bean
    MailService mailService(MailProperties mailProperties) {
        if (mailProperties.getProvider() == MailProperties.Provider.ZOHO_API) {
            if (!mailProperties.isConfigured()) {
                throw new IllegalStateException("Zoho Mail API configuration is incomplete: set MAIL_FROM, "
                        + "ZOHO_DATA_CENTER, ZOHO_MAIL_ACCOUNT_ID and all three ZOHO OAuth credentials.");
            }
            var client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofSeconds(10))
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .build();
            var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(client);
            factory.setReadTimeout(java.time.Duration.ofSeconds(15));
            log.info("Mail delivery uses Zoho Mail HTTPS API (data center={})",
                    mailProperties.getZoho().getDataCenter());
            return new ZohoApiMailService(mailProperties,
                    org.springframework.web.client.RestClient.builder().requestFactory(factory).build(),
                    java.time.Clock.systemUTC());
        }
        if (!mailProperties.isConfigured()) {
            log.warn("Mail delivery is DISABLED: set MAIL_HOST and MAIL_FROM to enable account-setup "
                    + "and password-recovery e-mail. Messages will be logged instead of sent.");
            return new LoggingMailService();
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(mailProperties.getHost());
        sender.setPort(mailProperties.getPort());
        sender.setUsername(mailProperties.getUsername());
        sender.setPassword(mailProperties.getPassword());
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", Boolean.toString(mailProperties.isSmtpAuth()));
        props.put("mail.smtp.starttls.enable", Boolean.toString(mailProperties.isStarttlsEnable()));
        props.put("mail.smtp.starttls.required", Boolean.toString(mailProperties.isStarttlsEnable()));
        // Implicit TLS (port 465) and STARTTLS (port 587) are mutually exclusive.
        props.put("mail.smtp.ssl.enable", Boolean.toString(mailProperties.isSslEnable()));
        props.put("mail.smtp.ssl.checkserveridentity", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        log.info("Mail delivery is ENABLED via {}:{} (ssl={}, starttls={})",
                mailProperties.getHost(), mailProperties.getPort(),
                mailProperties.isSslEnable(), mailProperties.isStarttlsEnable());
        return new SmtpMailService(sender, mailProperties);
    }
}
