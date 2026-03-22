package com.kubefn.runtime.server;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Simple admin endpoint authentication.
 * Protects /admin/* endpoints with basic auth when configured.
 *
 * <p>Set KUBEFN_ADMIN_TOKEN env var to enable.
 * Health/readiness endpoints (/healthz, /readyz) are always open.
 */
public class AdminAuth {

    private final String expectedToken;

    public AdminAuth() {
        this.expectedToken = System.getenv("KUBEFN_ADMIN_TOKEN");
    }

    /**
     * Check if a request is authorized.
     * Returns true if no auth is configured, or if the token matches.
     */
    public boolean isAuthorized(String path, String authHeader) {
        // Health/readiness always open (K8s probes need them)
        if ("/healthz".equals(path) || "/readyz".equals(path)) {
            return true;
        }

        // If no token configured, admin is open (dev mode)
        if (expectedToken == null || expectedToken.isEmpty()) {
            return true;
        }

        // Check Bearer token
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return expectedToken.equals(authHeader.substring(7));
        }

        // Check Basic auth
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            try {
                String decoded = new String(
                        Base64.getDecoder().decode(authHeader.substring(6)),
                        StandardCharsets.UTF_8);
                // Accept any username with the correct password
                String password = decoded.contains(":") ?
                        decoded.substring(decoded.indexOf(':') + 1) : decoded;
                return expectedToken.equals(password);
            } catch (Exception e) {
                return false;
            }
        }

        return false;
    }

    public boolean isEnabled() {
        return expectedToken != null && !expectedToken.isEmpty();
    }
}
