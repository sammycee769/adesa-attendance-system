package com.adesa.attendance.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;

import com.adesa.attendance.config.AccountProperties;
import org.junit.jupiter.api.Test;

class AccountMailComposerTest {
    @Test
    void allAccountMessagesIncludeSignatureNoticeAndEscapeNamesInHtml() {
        var properties = new AccountProperties();
        properties.setPublicUrl("https://attendance.adesa.test");
        var composer = new AccountMailComposer(properties);
        String name = "<img src=x onerror=alert(1)>";
        Stream.of(composer.accountSetup(name, "setup-token", 48),
                  composer.passwordRecovery(name, "reset-token", 30),
                  composer.passwordChanged(name))
                .forEach(mail -> {
                    assertThat(mail.textBody()).contains(name, "IT DEPARTMENT", "it@adesahq.com",
                            "This is an automated email", "Please do not reply.");
                    assertThat(mail.htmlBody()).contains("IT DEPARTMENT", "mailto:it@adesahq.com",
                            "This is an automated email", "&lt;img src=x onerror=alert(1)&gt;",
                            "https://attendance.adesa.test/assets/email/adesa-hq.png",
                            "https://attendance.adesa.test/assets/email/group-headquarters.png")
                            .doesNotContain(name, "/zm/ImageSignature", "{{PUBLIC_URL}}", "please reply to this message");
                });
        assertThat(composer.accountSetup("Employee", "setup-token", 48).htmlBody())
                .contains("https://attendance.adesa.test/setup-password?token=setup-token");
        assertThat(composer.passwordRecovery("Employee", "reset-token", 30).htmlBody())
                .contains("https://attendance.adesa.test/reset-password?token=reset-token");
    }
}
