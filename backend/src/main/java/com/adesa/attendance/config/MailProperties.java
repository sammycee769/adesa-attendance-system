package com.adesa.attendance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Zoho Mail (or any SMTP) configuration, read entirely from environment
 * variables so no credential is ever committed.
 *
 * <p>Port 465 uses implicit TLS ({@code MAIL_SMTP_SSL_ENABLE=true},
 * {@code MAIL_SMTP_STARTTLS_ENABLE=false}); port 587 uses STARTTLS
 * ({@code MAIL_SMTP_SSL_ENABLE=false}, {@code MAIL_SMTP_STARTTLS_ENABLE=true}).
 *
 * <p>When {@code host} is blank the application does not build an SMTP sender
 * at all and falls back to a logging stub, so a development or test run can
 * never send a real message.
 */
@Validated
@ConfigurationProperties(prefix = "adesa.mail")
public class MailProperties {

    /** SMTP host, e.g. {@code smtp.zoho.com} (data-centre specific). */
    private String host = "";

    private int port = 587;

    private String username = "";

    private String password = "";

    /** Envelope sender, e.g. {@code "ADESA HQ Attendance <no-reply@adesa.example>"}. */
    private String from = "";

    private boolean smtpAuth = true;

    private boolean starttlsEnable = true;

    private boolean sslEnable = false;

    /** True when a real SMTP transport should be built. */
    public boolean isConfigured() {
        return StringUtils.hasText(host) && StringUtils.hasText(from);
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public boolean isSmtpAuth() {
        return smtpAuth;
    }

    public void setSmtpAuth(boolean smtpAuth) {
        this.smtpAuth = smtpAuth;
    }

    public boolean isStarttlsEnable() {
        return starttlsEnable;
    }

    public void setStarttlsEnable(boolean starttlsEnable) {
        this.starttlsEnable = starttlsEnable;
    }

    public boolean isSslEnable() {
        return sslEnable;
    }

    public void setSslEnable(boolean sslEnable) {
        this.sslEnable = sslEnable;
    }
}
