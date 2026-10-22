package com.adesa.attendance.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import com.adesa.attendance.config.MailProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class ZohoApiMailServiceTest {
    private MailProperties properties;
    private MockRestServiceServer server;
    private ZohoApiMailService service;
    private final MutableClock clock = new MutableClock();

    @BeforeEach
    void setUp() {
        properties = configuredProperties();
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        service = new ZohoApiMailService(properties, builder.build(), clock);
    }

    static MailProperties configuredProperties() {
        var properties = new MailProperties();
        properties.setProvider(MailProperties.Provider.ZOHO_API);
        properties.setFrom("ADESA Attendance <attendance@adesa.test>");
        properties.getZoho().setDataCenter(MailProperties.ZohoDataCenter.EU);
        properties.getZoho().setAccountId("123456789");
        properties.getZoho().setClientId("test-client");
        properties.getZoho().setClientSecret("private-test-secret");
        properties.getZoho().setRefreshToken("private-test-refresh");
        return properties;
    }

    private void expectToken(String token) {
        server.expect(requestTo("https://accounts.zoho.eu/oauth/v2/token"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string(containsString("client_secret=private-test-secret")))
                .andExpect(content().string(containsString("refresh_token=private-test-refresh")))
                .andRespond(withSuccess("{\"access_token\":\"" + token + "\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
    }

    private void expectMail(String token, String response) {
        server.expect(requestTo("https://mail.zoho.eu/api/accounts/123456789/messages"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Zoho-oauthtoken " + token))
                .andExpect(content().json("""
                        {"fromAddress":"attendance@adesa.test","toAddress":"employee@adesa.test",
                         "subject":"Account setup","content":"Use the private setup link",
                         "mailFormat":"plaintext","encoding":"UTF-8"}
                        """))
                .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
    }

    private void send() {
        service.send("employee@adesa.test", "Account setup", "Use the private setup link", "<p>Setup</p>");
    }

    @Test
    void sendsOverHttpsAndReusesTheAccessToken() {
        expectToken("test-access");
        expectMail("test-access", "{\"status\":{\"code\":200}}");
        expectMail("test-access", "{\"status\":{\"code\":200}}");
        send();
        send();
        server.verify();
        assertThat(service.isDeliveryEnabled()).isTrue();
    }

    @Test
    void refreshesBeforeTheAccessTokenExpires() {
        expectToken("first-access");
        expectMail("first-access", "{\"status\":{\"code\":200}}");
        expectToken("new-access");
        expectMail("new-access", "{\"status\":{\"code\":200}}");
        send();
        clock.now = clock.now.plusSeconds(3550);
        send();
        server.verify();
    }

    @Test
    void aProviderErrorInHttp200IsStillADeliveryFailure() {
        expectToken("test-access");
        expectMail("test-access", "{\"status\":{\"code\":500},\"data\":{\"moreInfo\":\"private-link\"}}");
        assertThatThrownBy(this::send).isInstanceOf(MailDeliveryException.class)
                .hasMessage("The message could not be sent").hasNoCause();
        server.verify();
    }

    @Test
    void oauthRejectionDoesNotSendMailOrExposeProviderBody() {
        server.expect(requestTo("https://accounts.zoho.eu/oauth/v2/token"))
                .andRespond(withSuccess("{\"error\":\"invalid_client private-test-secret\"}",
                        MediaType.APPLICATION_JSON));
        assertThatThrownBy(this::send).isInstanceOf(MailDeliveryException.class)
                .hasMessage("The message could not be sent").hasNoCause();
        server.verify();
    }

    @Test
    void httpFailureDoesNotExposeEchoedCredentialsOrSetupLinks() {
        expectToken("test-access");
        server.expect(requestTo("https://mail.zoho.eu/api/accounts/123456789/messages"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .body("private-test-secret https://frontend.test/setup?token=private-link"));
        assertThatThrownBy(this::send).isInstanceOf(MailDeliveryException.class)
                .hasMessage("The message could not be sent").hasNoCause();
        server.verify();
    }

    @Test
    void incompleteCredentialsCannotFallBackToLoggingOrSmtp() {
        properties.getZoho().setRefreshToken("");
        assertThatThrownBy(() -> new MailConfig().mailService(properties))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete");
    }

    @Test
    void accountIdCannotInjectAPathAndDataCenterIsRequired() {
        properties.getZoho().setAccountId("123/../../messages");
        assertThat(properties.isConfigured()).isFalse();
        properties.getZoho().setAccountId("123");
        properties.getZoho().setDataCenter(null);
        assertThat(properties.isConfigured()).isFalse();
    }

    @Test
    void invalidOrMultipleRecipientsNeverReachZoho() {
        assertThatThrownBy(() -> service.send("one@adesa.test,two@adesa.test", "Setup", "link", "link"))
                .isInstanceOf(MailDeliveryException.class).hasNoCause();
        server.verify();
    }

    @Test
    void invalidSenderFailsConfiguration() {
        properties.setFrom("attacker@adesa.test\r\nBcc: other@adesa.test");
        assertThatThrownBy(() -> new ZohoApiMailService(properties, RestClient.create(), clock))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-22T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
    }
}
