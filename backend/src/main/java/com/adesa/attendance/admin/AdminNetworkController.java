package com.adesa.attendance.admin;

import com.adesa.attendance.admin.dto.NetworkDiagnosticsResponse;
import com.adesa.attendance.attendance.network.ClientIpResolver;
import com.adesa.attendance.attendance.network.OfficeNetworkPolicy;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;

/**
 * Network diagnostics for ADESA IT. ADMIN only.
 *
 * <p>Before the office-network restriction is switched on in production, IT has
 * to confirm that the application really resolves the office's public address
 * for requests that come through the office connection. This endpoint answers
 * exactly that question for the caller's own request — it is the verification
 * step of the production rollout (see the README).
 *
 * <p>It reports the resolved client address and the policy state. It never
 * returns the configured networks, the trusted proxies or the raw forwarding
 * headers.
 */
@RestController
@RequestMapping("/api/v1/admin/network")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Admin: Network", description = "Office-network diagnostics (ADMIN only)")
public class AdminNetworkController {

    private final ClientIpResolver clientIpResolver;
    private final OfficeNetworkPolicy officeNetworkPolicy;

    public AdminNetworkController(ClientIpResolver clientIpResolver,
                                  OfficeNetworkPolicy officeNetworkPolicy) {
        this.clientIpResolver = clientIpResolver;
        this.officeNetworkPolicy = officeNetworkPolicy;
    }

    @GetMapping("/client-address")
    @Operation(summary = "The client address the application sees for this request",
            description = "Requires ADMIN. Reports the resolved client address (forwarded headers are only "
                    + "honoured from configured trusted proxies), whether it is inside an approved office "
                    + "network, and whether the restriction is enforced. Use this from inside the office "
                    + "network to verify the client address before enabling enforcement in production. "
                    + "The raw forwarding header is included for troubleshooting only and is never used "
                    + "for the policy decision.")
    public NetworkDiagnosticsResponse clientAddress(HttpServletRequest request) {
        var address = clientIpResolver.resolve(request);
        return new NetworkDiagnosticsResponse(
                address != null ? address.toString() : null,
                officeNetworkPolicy.isWithinApprovedNetwork(address),
                officeNetworkPolicy.isEnforcementEnabled(),
                officeNetworkPolicy.approvedNetworkCount(),
                clientIpResolver.trustedProxyCount(),
                clientIpResolver.clientIpHeaderName(),
                address != null && address.isIPv4(),
                clientIpResolver.untrustedForwardedHeaderValue(request));
    }
}
