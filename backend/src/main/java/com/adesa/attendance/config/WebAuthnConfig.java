package com.adesa.attendance.config;

import com.webauthn4j.WebAuthnManager;
import com.webauthn4j.converter.util.ObjectConverter;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * WebAuthn wiring.
 *
 * <p>The non-strict manager still verifies the challenge, origin, RP id, user
 * presence/verification flags and the assertion signature. It does not attempt
 * to verify attestation trustworthiness (certificate chains): consumer
 * passkeys normally use "none" attestation, which carries no attestation
 * certificate. Device approval is an explicit administrator action, which is
 * the trust anchor in this system.
 */
@Configuration
public class WebAuthnConfig {

    @Bean
    public ObjectConverter webAuthnObjectConverter() {
        return new ObjectConverter();
    }

    @Bean
    public WebAuthnManager webAuthnManager() {
        return WebAuthnManager.createNonStrictWebAuthnManager();
    }
}
