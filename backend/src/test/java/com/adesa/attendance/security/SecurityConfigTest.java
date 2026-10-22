package com.adesa.attendance.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import jakarta.servlet.http.Cookie;

/**
 * The CSRF cookie is the one cookie the SPA must be able to read. Its
 * {@code Secure} flag therefore cannot rely on {@code request.isSecure()}: the
 * application deliberately disables forwarded-header processing, so a request
 * that arrives through the TLS-terminating proxy reports {@code false}. The
 * repository follows the session-cookie setting instead.
 *
 * <p>Servlet 6 stores {@code SameSite} as a cookie attribute, so the assertion
 * reads it from the cookie object rather than from the serialized header
 * (which the mock response does not produce).
 */
class SecurityConfigTest {

    private final SecurityConfig config = new SecurityConfig();

    @Test
    void csrfCookieIsSecureAndSameSiteLaxWhenTheSessionCookieIsSecure() {
        Cookie cookie = save(config.csrfTokenRepository(true));

        assertThat(cookie).isNotNull();
        assertThat(cookie.getName()).isEqualTo("XSRF-TOKEN");
        assertThat(cookie.getSecure()).isTrue();
        assertThat(cookie.isHttpOnly()).isFalse();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    @Test
    void csrfCookieStaysUsableOverPlainHttpInLocalDevelopment() {
        Cookie cookie = save(config.csrfTokenRepository(false));

        assertThat(cookie).isNotNull();
        assertThat(cookie.getName()).isEqualTo("XSRF-TOKEN");
        assertThat(cookie.getSecure()).isFalse();
        assertThat(cookie.getAttribute("SameSite")).isEqualTo("Lax");
    }

    private Cookie save(CookieCsrfTokenRepository repository) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        // A request that reached the app through a TLS-terminating proxy.
        request.setSecure(false);
        MockHttpServletResponse response = new MockHttpServletResponse();
        repository.saveToken(
                new DefaultCsrfToken("X-XSRF-TOKEN", "XSRF-TOKEN", "token-value"), request, response);
        return response.getCookie("XSRF-TOKEN");
    }
}
