package com.adesa.attendance.mail;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.adesa.attendance.config.MailProperties;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Zoho OAuth + Mail HTTPS API. No SMTP connection or browser-side credentials. */
public final class ZohoApiMailService implements MailService {

    private static final Logger log = LoggerFactory.getLogger(ZohoApiMailService.class);
    private final MailProperties.Zoho zoho;
    private final String from;
    private final RestClient client;
    private final Clock clock;
    private String cachedAccessToken;
    private Instant tokenExpiresAt = Instant.MIN;

    public ZohoApiMailService(MailProperties properties, RestClient client, Clock clock) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException("Zoho Mail API configuration is incomplete");
        }
        this.zoho = properties.getZoho();
        this.from = mailbox(properties.getFrom());
        this.client = client;
        this.clock = clock;
    }

    @Override
    public void send(String to, String subject, String textBody, String htmlBody) {
        try {
            String recipient = mailbox(to);
            String token = accessToken();
            JsonNode result = client.post()
                    .uri(zoho.getDataCenter().mailOrigin() + "/api/accounts/{accountId}/messages",
                            zoho.getAccountId())
                    .header("Authorization", "Zoho-oauthtoken " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("fromAddress", from, "toAddress", recipient,
                            "subject", subject, "content", textBody,
                            "mailFormat", "plaintext", "encoding", "UTF-8"))
                    .retrieve().body(JsonNode.class);
            // Zoho can report an application error in a successful HTTP response.
            if (result == null || result.path("status").path("code").asInt() != 200) {
                throw deliveryFailure();
            }
            log.info("Zoho Mail API accepted an account message");
        } catch (RestClientException | IllegalArgumentException ex) {
            // Exception messages/response bodies may contain OAuth credentials or
            // echoed setup links. Deliberately do not retain the provider cause.
            throw deliveryFailure();
        }
    }

    private synchronized String accessToken() {
        Instant now = clock.instant();
        if (cachedAccessToken != null && now.isBefore(tokenExpiresAt)) {
            return cachedAccessToken;
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", zoho.getRefreshToken());
        form.add("client_id", zoho.getClientId());
        form.add("client_secret", zoho.getClientSecret());
        JsonNode response = client.post()
                .uri(zoho.getDataCenter().accountsOrigin() + "/oauth/v2/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(JsonNode.class);
        String token = response == null ? "" : response.path("access_token").asText("");
        long ttl = response == null ? 0 : response.path("expires_in").asLong();
        if (response == null || response.has("error") || token.isBlank() || ttl <= 0) {
            throw deliveryFailure();
        }
        // Refresh before expiry, bounded to the documented one-hour token life.
        ttl = Math.min(ttl, 3600);
        tokenExpiresAt = now.plusSeconds(ttl - Math.min(60, ttl / 10));
        cachedAccessToken = token;
        return token;
    }

    private static String mailbox(String value) {
        try {
            if (value == null || value.contains("\r") || value.contains("\n")) {
                throw new AddressException();
            }
            InternetAddress[] addresses = InternetAddress.parse(value, true);
            if (addresses.length != 1 || addresses[0].isGroup()) {
                throw new AddressException();
            }
            addresses[0].validate();
            return addresses[0].getAddress();
        } catch (AddressException ex) {
            throw new IllegalArgumentException("A single valid mail address is required");
        }
    }

    private static MailDeliveryException deliveryFailure() {
        log.warn("Zoho Mail API delivery failed; check provider credentials, permissions and quota privately");
        return new MailDeliveryException("The message could not be sent", null);
    }

    @Override
    public boolean isDeliveryEnabled() { return true; }
}
