package com.adesa.attendance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

/**
 * Zoho HTTPS API or SMTP configuration, read entirely from environment
 * variables so no credential is ever committed.
 *
 * <p>Port 465 uses implicit TLS ({@code MAIL_SMTP_SSL_ENABLE=true},
 * {@code MAIL_SMTP_STARTTLS_ENABLE=false}); port 587 uses STARTTLS
 * ({@code MAIL_SMTP_SSL_ENABLE=false}, {@code MAIL_SMTP_STARTTLS_ENABLE=true}).
 *
 * <p>With the SMTP provider and a blank host, development uses a logging stub.
 * Production requires a configured provider. Selecting Zoho API always requires
 * complete OAuth configuration and never falls back to logging.
 */
@Validated
@ConfigurationProperties(prefix = "adesa.mail")
public class MailProperties {

    public enum Provider { SMTP, ZOHO_API }

    public enum ZohoDataCenter {
        US("zoho.com"), EU("zoho.eu"), IN("zoho.in"), AU("zoho.com.au"),
        JP("zoho.jp"), CA("zohocloud.ca"), CN("zoho.com.cn"), AE("zoho.ae"), SA("zoho.sa");

        private final String domain;

        ZohoDataCenter(String domain) { this.domain = domain; }
        public String mailOrigin() { return "https://mail." + domain; }
        public String accountsOrigin() { return "https://accounts." + domain; }
    }

    public static class Zoho {
        private ZohoDataCenter dataCenter;
        private String accountId = "";
        private String clientId = "";
        private String clientSecret = "";
        private String refreshToken = "";

        public ZohoDataCenter getDataCenter() { return dataCenter; }
        public void setDataCenter(ZohoDataCenter value) { dataCenter = value; }
        public String getAccountId() { return accountId; }
        public void setAccountId(String value) { accountId = value; }
        public String getClientId() { return clientId; }
        public void setClientId(String value) { clientId = value; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String value) { clientSecret = value; }
        public String getRefreshToken() { return refreshToken; }
        public void setRefreshToken(String value) { refreshToken = value; }

        public boolean isConfigured() {
            return dataCenter != null && accountId != null && accountId.matches("[0-9]+")
                    && StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret)
                    && StringUtils.hasText(refreshToken);
        }
    }

    private Provider provider = Provider.SMTP;
    private final Zoho zoho = new Zoho();

    public Provider getProvider() { return provider; }
    public void setProvider(Provider value) { provider = value; }
    public Zoho getZoho() { return zoho; }

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

    /** True when the selected delivery provider has its required configuration. */
    public boolean isConfigured() {
        return StringUtils.hasText(from) && (provider == Provider.ZOHO_API
                ? zoho.isConfigured() : provider == Provider.SMTP && StringUtils.hasText(host));
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
