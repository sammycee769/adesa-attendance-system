package com.adesa.attendance.security;

import java.util.function.Supplier;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * CSRF request handler for a single-page application.
 *
 * <p>Spring Security's default XOR handler protects form posts but does not
 * accept a raw token from a JavaScript client. This handler accepts a
 * plain-text token from the request header (used by the SPA, which reads the
 * {@code XSRF-TOKEN} cookie) while keeping the XOR-protected behaviour for any
 * non-header source. It also forces the token to be written to the cookie on
 * every request so the SPA always has a fresh value.
 */
public final class SpaCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestHandler plain = new CsrfTokenRequestAttributeHandler();
    private final CsrfTokenRequestHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        xor.handle(request, response, csrfToken);
        // Force the token to be resolved now, so a missing token is generated
        // and written to the XSRF-TOKEN cookie during this request instead of
        // being deferred until something happens to read it.
        request.setAttribute("adesa.rawCsrfToken", csrfToken.get());
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        return (StringUtils.hasText(headerValue) ? plain : xor).resolveCsrfTokenValue(request, csrfToken);
    }
}
