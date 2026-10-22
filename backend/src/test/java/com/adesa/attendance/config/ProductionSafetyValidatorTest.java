package com.adesa.attendance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

/**
 * Unit tests for the production configuration safety guard.
 *
 * <p>Verifies that production configuration can never accidentally disable the
 * office-network restriction, start without approved CIDRs, use the development
 * database password, omit Secure cookies, or bind WebAuthn to localhost.
 */
class ProductionSafetyValidatorTest {

    private Environment environment;
    private AttendanceProperties properties;
    private WebAuthnProperties webAuthnProperties;
    private AccountProperties accountProperties;
    private MailProperties mailProperties;

    @BeforeEach
    void setUp() {
        environment = mock(Environment.class);
        properties = new AttendanceProperties();
        webAuthnProperties = new WebAuthnProperties();
        accountProperties = new AccountProperties();
        mailProperties = new MailProperties();

        // Safe production defaults for the checks added by the security review.
        when(environment.getProperty("spring.datasource.password"))
                .thenReturn("a-unique-production-password");
        when(environment.getProperty(
                        "server.servlet.session.cookie.secure", Boolean.class, Boolean.FALSE))
                .thenReturn(Boolean.TRUE);
        webAuthnProperties.setRpId("attendance.adesa.example");
        webAuthnProperties.setAllowedOrigins(new ArrayList<>(
                List.of("https://attendance.adesa.example")));
        webAuthnProperties.setUserVerificationRequired(true);
        accountProperties.setPublicUrl("https://attendance.adesa.example");
        mailProperties.setHost("smtp.example.test");
        mailProperties.setFrom("Attendance <noreply@example.test>");
        mailProperties.setSmtpAuth(false);
    }

    private ProductionSafetyValidator validator() {
        return new ProductionSafetyValidator(
                environment, properties, webAuthnProperties, accountProperties, mailProperties);
    }

    private void prod() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"prod"});
    }

    @Test
    @DisplayName("Non-production profile never triggers the guard")
    void nonProdProfileIsIgnored() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        properties.getNetwork().setEnforcementEnabled(false);
        properties.getNetwork().setAllowedIpv4(List.of());

        validator().afterPropertiesSet();
    }

    @Test
    @DisplayName("No active profile never triggers the prod guard")
    void noActiveProfileIsIgnored() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {});
        properties.getNetwork().setEnforcementEnabled(false);

        validator().afterPropertiesSet();
    }

    @Test
    @DisplayName("The development bypass is refused outside the dev profile")
    void bypassWithoutDevProfileFails() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {});
        properties.getNetwork().setLocalDevelopmentBypass(true);

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local-development-bypass")
                .hasMessageContaining("'dev' profile");
    }

    @Test
    @DisplayName("The development bypass is accepted with the dev profile")
    void bypassWithDevProfilePasses() {
        when(environment.getActiveProfiles()).thenReturn(new String[] {"dev"});
        properties.getNetwork().setLocalDevelopmentBypass(true);

        validator().afterPropertiesSet();
    }

    @Test
    @DisplayName("Prod profile with network restriction disabled fails startup")
    void prodWithRestrictionDisabledFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(false);
        properties.getNetwork().setAllowedIpv4(List.of("192.0.2.0/24"));

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("enforcement-enabled");
    }

    @Test
    @DisplayName("Prod profile with no approved CIDRs fails startup")
    void prodWithEmptyCidrsFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of());

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("allowed-ipv4");
    }

    @Test
    @DisplayName("Prod profile with blank CIDR entry fails startup")
    void prodWithBlankCidrFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("  "));

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("blank entry");
    }

    @Test
    @DisplayName("Prod profile with an over-broad allowlist fails startup")
    void prodWithOverBroadCidrFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("0.0.0.0/0"));

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("over-broad");
    }

    @Test
    @DisplayName("Prod profile with a non-IPv4 allowlist entry fails startup")
    void prodWithIpv6AllowlistEntryFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("2001:db8::/48"));

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IPv4-only");
    }

    @Test
    @DisplayName("Prod profile with a trusted-proxy range fails startup")
    void prodWithTrustedProxyRangeFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("105.127.6.174/32"));
        properties.getNetwork().setTrustedProxies(List.of("10.0.0.0/8"));

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("trusted-proxies")
                .hasMessageContaining("/32");
    }

    @Test
    @DisplayName("Prod profile with the development database password fails startup")
    void prodWithDevelopmentDatabasePasswordFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("105.127.6.174/32"));
        when(environment.getProperty("spring.datasource.password"))
                .thenReturn(ProductionSafetyValidator.DEV_DATABASE_PASSWORD);

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("development default");
    }

    @Test
    @DisplayName("Prod profile with an insecure session cookie fails startup")
    void prodWithInsecureSessionCookieFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("105.127.6.174/32"));
        when(environment.getProperty(
                        "server.servlet.session.cookie.secure", Boolean.class, Boolean.FALSE))
                .thenReturn(Boolean.FALSE);

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cookie.secure");
    }

    @Test
    @DisplayName("Prod profile with a localhost WebAuthn RP ID fails startup")
    void prodWithLocalhostRpIdFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("105.127.6.174/32"));
        webAuthnProperties.setRpId("localhost");

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("webauthn.rp-id");
    }

    @Test
    @DisplayName("Prod profile with a non-HTTPS WebAuthn origin fails startup")
    void prodWithNonHttpsOriginFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("105.127.6.174/32"));
        webAuthnProperties.setAllowedOrigins(
                new ArrayList<>(List.of("http://attendance.adesa.example")));

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");
    }

    @Test
    @DisplayName("Prod profile with an origin outside the RP ID fails startup")
    void prodWithOriginOutsideRpIdFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("105.127.6.174/32"));
        webAuthnProperties.setAllowedOrigins(
                new ArrayList<>(List.of("https://attendance.example.net")));

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not within");
    }

    @Test
    @DisplayName("Prod profile without WebAuthn user verification fails startup")
    void prodWithoutUserVerificationFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("105.127.6.174/32"));
        webAuthnProperties.setUserVerificationRequired(false);

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("user-verification-required");
    }

    @Test
    @DisplayName("Prod profile with an unsafe public URL fails startup")
    void prodWithUnsafePublicUrlFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("105.127.6.174/32"));
        accountProperties.setPublicUrl("http://attendance.adesa.example");

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("APP_PUBLIC_URL must use https");
    }

    @Test
    @DisplayName("Prod profile without configured mail delivery fails startup")
    void prodWithoutConfiguredMailFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("105.127.6.174/32"));
        mailProperties.setHost("");

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mail delivery is not configured");
    }

    @Test
    @DisplayName("Prod profile with restriction enabled and safe configuration starts")
    void prodWithValidConfigPasses() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setAllowedIpv4(List.of("105.127.6.174/32", "198.51.100.10/32"));

        validator().afterPropertiesSet();

        assertThat(properties.getNetwork().isEnforcementEnabled()).isTrue();
    }

    @Test
    @DisplayName("Prod profile with the local-development bypass set fails startup")
    void prodWithLocalDevelopmentBypassFails() {
        prod();
        properties.getNetwork().setEnforcementEnabled(true);
        properties.getNetwork().setLocalDevelopmentBypass(true);
        properties.getNetwork().setAllowedIpv4(List.of("105.127.6.174/32"));

        assertThatThrownBy(() -> validator().afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("local-development-bypass");
    }

    @Test
    @DisplayName("The prod profile never enables the office-network restriction implicitly")
    void prodProfileDoesNotEnableEnforcementByDefault() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yml"));

        PropertySource<?> source = sources.get(0);

        // The default must stay 'false': enforcement is switched on deliberately,
        // after the office client address has been verified.
        assertThat(source.getProperty("attendance.network.enforcement-enabled"))
                .isEqualTo("${ATTENDANCE_NETWORK_ENFORCEMENT_ENABLED:false}");
    }

    @Test
    @DisplayName("The prod profile requires an explicitly supplied database password")
    void prodProfileHasNoDefaultDatabasePassword() throws IOException {
        List<PropertySource<?>> sources =
                new YamlPropertySourceLoader().load("prod", new ClassPathResource("application-prod.yml"));

        PropertySource<?> source = sources.get(0);

        assertThat(source.getProperty("spring.datasource.password"))
                .isEqualTo("${DB_PASSWORD}");
    }
}
