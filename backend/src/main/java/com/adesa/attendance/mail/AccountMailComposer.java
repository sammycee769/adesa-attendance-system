package com.adesa.attendance.mail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import com.adesa.attendance.config.AccountProperties;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Builds the absolute account-setup and password-recovery links and the
 * matching e-mail bodies.
 *
 * <p>Links are built exclusively from {@code adesa.account.public-url} (the
 * {@code APP_PUBLIC_URL} environment variable). The incoming {@code Host}
 * header is never used, so a forged header cannot redirect a recovery link to
 * an attacker-controlled domain (host-header injection is therefore not
 * possible here).
 */
@Component
public class AccountMailComposer {

    private static final String AUTOMATED_NOTICE =
            "This is an automated email from the ADESA HQ Attendance system. Please do not reply.";
    private static final String SIGNATURE_HTML = loadSignature();
    private static final String SIGNATURE_TEXT = """
            IT DEPARTMENT
            ADESA HQ
            www.adesahq.com
            +2347014570442
            it@adesahq.com
            2 Isheri Road, Beside River Valley Estate (Gate A), Ojodu Berger, Lagos State.

            The content of this email is confidential and intended only for the named recipient.
            Do not share it without the sender's written consent. If you received it by mistake,
            contact it@adesahq.com and delete it.
            """;

    private final AccountProperties accountProperties;

    public AccountMailComposer(AccountProperties accountProperties) {
        this.accountProperties = accountProperties;
    }

    /** A composed message ready for {@link MailService}. */
    public record ComposedMail(String subject, String textBody, String htmlBody) {
    }

    public ComposedMail accountSetup(String fullName, String rawToken, long ttlHours) {
        String link = buildLink("/setup-password", rawToken);
        String subject = "ADESA HQ Attendance — set up your account";
        String text = """
                Hello %s,

                An ADESA HQ Attendance account has been created for you.

                Choose your password using the link below. The link can be used once and
                expires in %d hours:

                %s

                If you were not expecting this message, contact ADESA HR. Do not forward
                this link to anyone.

                """.formatted(displayName(fullName), ttlHours, link);
        String html = wrapHtml("""
                <p>Hello %s,</p>
                <p>An <strong>ADESA HQ Attendance</strong> account has been created for you.</p>
                <p>Choose your password using the button below. The link can be used once and
                expires in %d hours.</p>
                <p><a href="%s" style="background:#0b5cab;color:#ffffff;padding:12px 20px;border-radius:6px;text-decoration:none;display:inline-block">Set up my password</a></p>
                <p style="word-break:break-all"><a href="%s">%s</a></p>
                <p>If you were not expecting this message, contact ADESA HR. Do not forward this
                link to anyone.</p>
                """.formatted(htmlDisplayName(fullName), ttlHours, link, link, link));
        return compose(subject, text, html);
    }

    public ComposedMail passwordRecovery(String fullName, String rawToken, long ttlMinutes) {
        String link = buildLink("/reset-password", rawToken);
        String subject = "ADESA HQ Attendance — password reset";
        String text = """
                Hello %s,

                A password reset was requested for your ADESA HQ Attendance account.

                Choose a new password using the link below. The link can be used once and
                expires in %d minutes:

                %s

                If you did not request this, you can ignore this message — your password
                will not change. Do not forward this link to anyone.

                """.formatted(displayName(fullName), ttlMinutes, link);
        String html = wrapHtml("""
                <p>Hello %s,</p>
                <p>A password reset was requested for your <strong>ADESA HQ Attendance</strong> account.</p>
                <p>Choose a new password using the button below. The link can be used once and
                expires in %d minutes.</p>
                <p><a href="%s" style="background:#0b5cab;color:#ffffff;padding:12px 20px;border-radius:6px;text-decoration:none;display:inline-block">Reset my password</a></p>
                <p style="word-break:break-all"><a href="%s">%s</a></p>
                <p>If you did not request this, you can ignore this message — your password will
                not change. Do not forward this link to anyone.</p>
                """.formatted(htmlDisplayName(fullName), ttlMinutes, link, link, link));
        return compose(subject, text, html);
    }

    public ComposedMail passwordChanged(String fullName) {
        String subject = "ADESA HQ Attendance — your password was changed";
        String text = """
                Hello %s,

                Your ADESA HQ Attendance password was changed. Your other active sessions
                have been signed out.

                If this was not you, contact ADESA HR immediately.

                """.formatted(displayName(fullName));
        String html = wrapHtml("""
                <p>Hello %s,</p>
                <p>Your <strong>ADESA HQ Attendance</strong> password was changed. Your other
                active sessions have been signed out.</p>
                <p>If this was not you, contact ADESA HR immediately.</p>
                """.formatted(htmlDisplayName(fullName)));
        return compose(subject, text, html);
    }

    /** Builds an absolute link from the configured public URL only. */
    String buildLink(String path, String rawToken) {
        return UriComponentsBuilder.fromUriString(accountProperties.getPublicUrl())
                .path(path)
                .queryParam("token", rawToken)
                .build()
                .toUriString();
    }

    private static String displayName(String fullName) {
        return fullName == null || fullName.isBlank() ? "there" : fullName.trim();
    }

    private static String htmlDisplayName(String fullName) {
        return HtmlUtils.htmlEscape(displayName(fullName));
    }

    private static ComposedMail compose(String subject, String text, String html) {
        return new ComposedMail(subject, text + SIGNATURE_TEXT + "\n" + AUTOMATED_NOTICE + "\n", html);
    }

    private static String loadSignature() {
        try (var input = new ClassPathResource("mail/it-signature.html").getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException("Could not load the ADESA email signature", ex);
        }
    }

    private String wrapHtml(String content) {
        String publicUrl = accountProperties.getPublicUrl().replaceAll("/+$", "");
        String signature = SIGNATURE_HTML.replace("{{PUBLIC_URL}}", HtmlUtils.htmlEscape(publicUrl));
        return """
                <!doctype html>
                <html>
                <body style="font-family:Arial,Helvetica,sans-serif;color:#1f2933;line-height:1.5">
                  <div style="max-width:650px;margin:0 auto;padding:24px">
                    <h2 style="color:#0b5cab;margin:0 0 16px">ADESA HQ Attendance</h2>
                    %s
                    <hr style="border:none;border-top:1px solid #e4e7eb;margin:24px 0">
                    %s
                    <p style="font-size:12px;color:#7b8794">%s</p>
                  </div>
                </body>
                </html>
                """.formatted(content, signature, AUTOMATED_NOTICE);
    }
}
