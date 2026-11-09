package com.adesa.attendance.assets;

import com.adesa.attendance.mail.MailService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssetMailDispatcherTest {
    private AssetMail message() {
        var message = new AssetMail();
        message.recipientEmail = "synthetic@example.test";
        message.subject = "ADESA request submitted";
        message.textBody = "Synthetic request <script>must be escaped</script>\nLine 2";
        return message;
    }

    private EntityManager entityManager(AssetMail message) {
        var em = mock(EntityManager.class);
        var query = mock(Query.class);
        when(em.createNativeQuery(anyString(), eq(AssetMail.class))).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(message));
        return em;
    }

    @Test void capturesLocalMailAndEscapesHtml() {
        var message = message(); var mail = mock(MailService.class);
        new AssetMailDispatcher(entityManager(message), mail).deliver();
        verify(mail).send(eq(message.recipientEmail), eq(message.subject), eq(message.textBody),
                contains("&lt;script&gt;must be escaped&lt;/script&gt;<br>"));
        assertThat(message.status).isEqualTo("LOCAL_CAPTURED");
        assertThat(message.deliveredAt).isNotNull();
        assertThat(message.attempts).isEqualTo(1);
    }

    @Test void recordsRealTransportAcceptanceSeparatelyFromLocalCapture() {
        var message = message(); var mail = mock(MailService.class);
        when(mail.isDeliveryEnabled()).thenReturn(true);
        new AssetMailDispatcher(entityManager(message), mail).deliver();
        assertThat(message.status).isEqualTo("SENT");
    }

    @Test void deliveryFailureSchedulesRetryWithoutFailingTheRequest() {
        var message = message(); var mail = mock(MailService.class);
        doThrow(new IllegalStateException("Synthetic unavailable transport")).when(mail).send(anyString(), anyString(), anyString(), anyString());
        new AssetMailDispatcher(entityManager(message), mail).deliver();
        assertThat(message.status).isEqualTo("RETRY");
        assertThat(message.nextAttemptAt).isAfter(Instant.now());
        assertThat(message.deliveredAt).isNull();
        message.attempts = 7;
        new AssetMailDispatcher(entityManager(message), mail).deliver();
        assertThat(message.status).isEqualTo("FAILED");
    }
}
