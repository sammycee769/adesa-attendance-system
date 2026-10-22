package com.adesa.attendance.security;

import com.adesa.attendance.config.AuthProperties;
import com.adesa.attendance.config.AccountProperties;
import java.util.List;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import com.adesa.attendance.employee.EmployeeRepository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Security filter chain.
 *
 * <p>Server-side authorization only — hiding buttons in the frontend is never a
 * control. Sessions are server-managed with {@code Secure}/{@code HttpOnly}/
 * {@code SameSite} cookies, CSRF protection for the SPA, session-id rotation at
 * login (Spring's default fixation protection) and two extra guards:
 * {@link AbsoluteSessionTimeoutFilter} and {@link CredentialsVersionFilter}.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * The CSRF token repository used by the security chain.
     *
     * <p>Exposed as a bean (instead of a local variable) so tests can restore it:
     * spring-security-test's {@code csrf()} request post-processor replaces the
     * {@code CsrfFilter} repository with a test double by reflection and never
     * puts the real one back.
     */
    @Bean
    CookieCsrfTokenRepository csrfTokenRepository(
            @Value("${server.servlet.session.cookie.secure:false}") boolean cookieSecure,
            @Value("${server.servlet.session.cookie.same-site:lax}") String sameSite) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        // The repository otherwise derives `Secure` from request.isSecure(),
        // which is false behind a TLS-terminating reverse proxy because
        // forward-headers-strategy is deliberately NONE. Follow the session
        // cookie setting instead so the JS-readable token is Secure in prod.
        repository.setCookieCustomizer(builder -> builder
                .secure(cookieSecure)
                .sameSite(sameSite));
        return repository;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http,
                                            ApiAuthenticationEntryPoint entryPoint,
                                            ApiAccessDeniedHandler accessDeniedHandler,
                                            SecurityErrorWriter securityErrorWriter,
                                            AuthProperties authProperties,
                                            EmployeeRepository employeeRepository,
                                            CookieCsrfTokenRepository csrfTokenRepository)
            throws Exception {
        // Built here (not as beans) so they run only inside this chain and
        // never as plain servlet filters.
        AbsoluteSessionTimeoutFilter absoluteSessionTimeoutFilter =
                new AbsoluteSessionTimeoutFilter(authProperties, securityErrorWriter);
        CredentialsVersionFilter credentialsVersionFilter =
                new CredentialsVersionFilter(employeeRepository, securityErrorWriter);
        PasswordChangeRequiredFilter passwordChangeRequiredFilter =
                new PasswordChangeRequiredFilter(employeeRepository, securityErrorWriter);

        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfTokenRepository)
                        .csrfTokenRequestHandler(new SpaCsrfTokenRequestHandler()))
                .headers(headers -> headers
                        .frameOptions(frame -> frame.deny())
                        .contentTypeOptions(Customizer.withDefaults())
                        .referrerPolicy(referrer ->
                                referrer.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                        // The backend never serves HTML. A restrictive policy
                        // protects any accidental document response; the SPA is
                        // served by the reverse proxy, which must set its own.
                        .contentSecurityPolicy(csp -> csp
                                .policyDirectives("default-src 'none'; frame-ancestors 'none'; base-uri 'none'")))
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // No HTML login page, no HTTP Basic, no redirect on failure.
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                // Pure API: never store a "saved request" for a redirect.
                .requestCache(cache -> cache.disable())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/**",
                                "/actuator/info")
                        .permitAll()
                        // Authentication entry points: reachable without a session
                        // (they are still CSRF-protected where they mutate state).
                        .requestMatchers(
                                "/api/v1/auth/login",
                                "/api/v1/auth/logout",
                                "/api/v1/auth/csrf")
                        .permitAll()
                        // Password setup / recovery: reached before there is any
                        // session. Rate limited per client address; the
                        // responses are generic and CSRF-protected.
                        .requestMatchers(
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/password-token",
                                "/api/v1/auth/reset-password")
                        .permitAll()
                        // HR may read the roster, the attendance reports and the
                        // relevant audit entries. Every method still carries its
                        // own @PreAuthorize, so the filter chain is only the
                        // first line of defence.
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/admin/employees",
                                "/api/v1/admin/employees/**")
                        .hasAnyRole("HR", "ADMIN")
                        .requestMatchers("/api/v1/admin/attendance/**")
                        .hasAnyRole("HR", "ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/v1/admin/audit-logs")
                        .hasAnyRole("HR", "ADMIN")
                        // Everything else under /admin (creating employees,
                        // changing roles, approving devices, network
                        // diagnostics) stays ADMIN-only.
                        .requestMatchers("/api/v1/admin/**")
                        .hasRole("ADMIN")
                        .anyRequest()
                        .authenticated())
                .addFilterAfter(absoluteSessionTimeoutFilter, SecurityContextHolderFilter.class)
                .addFilterAfter(credentialsVersionFilter, SecurityContextHolderFilter.class)
                .addFilterAfter(passwordChangeRequiredFilter, CredentialsVersionFilter.class);
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AccountProperties accountProperties) {
        CorsConfiguration cors = new CorsConfiguration();
        // One exact frontend origin; no wildcard or deployment-preview domains.
        String origin = accountProperties.getPublicUrl().replaceAll("/+$", "");
        cors.setAllowedOrigins(List.of(origin));
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        cors.setAllowedHeaders(List.of("Accept", "Content-Type", "X-XSRF-TOKEN"));
        cors.setAllowCredentials(true);
        cors.setMaxAge(600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", cors);
        return source;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Passwords are stored only as hashes; bcrypt cost is Security's default.
        return new BCryptPasswordEncoder();
    }
}
