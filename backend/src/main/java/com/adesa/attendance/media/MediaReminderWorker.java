package com.adesa.attendance.media;

import com.adesa.attendance.mail.MailDeliveryException;
import com.adesa.attendance.mail.MailService;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;

@Component
@ConditionalOnProperty(name = "ADESA_MEDIA_REMINDERS_ENABLED", havingValue = "true")
class MediaReminderWorker {
    private static final Logger log = LoggerFactory.getLogger(MediaReminderWorker.class);
    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private final JdbcTemplate jdbc;
    private final MediaImportService imports;
    private final MailService mail;
    private final String publicUrl;

    MediaReminderWorker(MediaImportService imports, MailService mail,
            @Value("${ADESA_MEDIA_DB_URL:}") String url,
            @Value("${ADESA_MEDIA_WORKER_DB_USERNAME:}") String username,
            @Value("${ADESA_MEDIA_WORKER_DB_PASSWORD:}") String password,
            @Value("${ADESA_MEDIA_ALLOW_LIVE_MAIL:false}") boolean allowLive,
            @Value("${APP_PUBLIC_URL:}") String publicUrl) {
        if (url.isBlank() || username.isBlank() || password.isBlank() || publicUrl.isBlank()) {
            throw new IllegalStateException("Media reminders require a restricted worker login and the public app URL");
        }
        if (mail.isDeliveryEnabled() && !allowLive) {
            throw new IllegalStateException("Live Media reminder delivery has not been approved");
        }
        this.imports = imports;
        this.mail = mail;
        this.jdbc = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
        this.jdbc.setQueryTimeout(10);
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    @Scheduled(fixedDelayString = "${adesa.media.reminders.poll-ms:60000}", initialDelayString = "60000")
    void tick() {
        LocalDate day = LocalDate.now(LAGOS);
        jdbc.queryForObject("select media_private.prepare_reminders(?)", Integer.class, day);
        for (int count = 0; count < 25; count++) {
            String claimed = jdbc.queryForObject("select media_private.claim_reminder(?)::text", String.class, day);
            if (claimed == null) return;
            var item = imports.parse(claimed);
            String outcome = deliver(item, day);
            jdbc.queryForObject("select media_private.finish_reminder(?, ?, ?)::text", String.class,
                    UUID.fromString(item.path("id").asText()), UUID.fromString(item.path("claim_token").asText()), outcome);
        }
    }

    private String deliver(JsonNode item, LocalDate day) {
        var payload = item.path("payload");
        if (!payload.isObject() || !LocalDate.now(LAGOS).equals(day)) return "CANCELLED";
        String title = "ADESA Media | Contract expiry";
        String link = publicUrl + "/media/boards?board=" + payload.path("boardId").asText();
        String text = """
                ADESA Media
                Contract expiry
                Board: %s · %s
                Vendor: %s
                Client: %s
                Contract side: %s
                Start date: %s
                Expiry date: %s
                Contract amount (NGN): %s

                View the board: %s
                """.formatted(field(payload,"board"), field(payload,"location"), field(payload,"vendor"),
                field(payload,"client"), field(payload,"side"), field(payload,"start"), field(payload,"date"),
                field(payload,"amount"), link);
        try {
            mail.send(item.path("verified_email").asText(), title, text,
                    "<html><body><h2>ADESA Media</h2><p>" + HtmlUtils.htmlEscape(text).replace("\n", "<br>")
                            + "</p><a href=\"" + HtmlUtils.htmlEscape(link) + "\">View board</a></body></html>");
            return mail.isDeliveryEnabled() ? "SENT" : "LOCAL_CAPTURED";
        } catch (MailDeliveryException ex) {
            log.warn("Media reminder {} has uncertain delivery", item.path("id").asText());
            // A provider may have accepted the message before returning an error.
            return mail.isDeliveryEnabled() ? "UNKNOWN" : "RETRY";
        }
    }

    private static String field(JsonNode payload, String name) { return payload.path(name).asText("—"); }
}
