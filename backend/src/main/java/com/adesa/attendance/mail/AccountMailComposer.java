package com.adesa.attendance.mail;

import com.adesa.attendance.config.AccountProperties;

import org.springframework.stereotype.Component;
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

                ADESA HQ Attendance
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
                """.formatted(displayName(fullName), ttlHours, link, link, link));
        return new ComposedMail(subject, text, html);
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

                ADESA HQ Attendance
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
                """.formatted(displayName(fullName), ttlMinutes, link, link, link));
        return new ComposedMail(subject, text, html);
    }

    public ComposedMail passwordChanged(String fullName) {
        String subject = "ADESA HQ Attendance — your password was changed";
        String text = """
                Hello %s,

                Your ADESA HQ Attendance password was changed. Your other active sessions
                have been signed out.

                If this was not you, contact ADESA HR immediately.

                ADESA HQ Attendance
                """.formatted(displayName(fullName));
        String html = wrapHtml("""
                <p>Hello %s,</p>
                <p>Your <strong>ADESA HQ Attendance</strong> password was changed. Your other
                active sessions have been signed out.</p>
                <p>If this was not you, contact ADESA HR immediately.</p>
                """.formatted(displayName(fullName)));
        return new ComposedMail(subject, text, html);
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

    private static String wrapHtml(String content) {
        return """
                <!doctype html>
                <html>
                <body style="font-family:Arial,Helvetica,sans-serif;color:#1f2933;line-height:1.5">
                  <div style="max-width:560px;margin:0 auto;padding:24px">
                    <h2 style="color:#0b5cab;margin:0 0 16px">ADESA HQ Attendance</h2>
                    %s
                    <hr style="border:none;border-top:1px solid #e4e7eb;margin:24px 0">
                    <p style="font-size:12px;color:#7b8794">This is an automated message from the
                    ADESA HQ Attendance system. Please do not reply.</p>
                  </div>
                </body>
                </html>
                """.formatted(content);
    }
}
