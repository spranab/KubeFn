package com.kubefn.examples.usecases.sidecars;

import com.kubefn.api.*;

import java.time.Instant;
import java.util.*;
import java.util.logging.Logger;

/**
 * AuthMiddlewareFunction — Replaces Envoy ext_authz / OPA sidecar containers.
 *
 * BEFORE (traditional K8s):
 *   - Every pod has an Envoy sidecar (or OPA sidecar) for auth
 *   - 50 pods = 50 sidecar containers, each consuming 64-128MB RAM
 *   - Total overhead: 3.2-6.4 GB just for auth sidecars across the cluster
 *   - Each sidecar independently validates JWTs, fetches JWKS, caches results
 *   - Request flow: client -> Envoy sidecar -> ext_authz check -> app container
 *
 * AFTER (KubeFn):
 *   - One auth function validates tokens and publishes AuthContext to heap
 *   - ALL downstream functions read the authenticated user context zero-copy
 *   - No sidecar overhead — a single function serves the entire organism
 *   - Token validation result is shared, not repeated per-function
 */
@FnRoute(path = "/auth/verify", methods = {"POST"})
@FnGroup("platform-security")
public class AuthMiddlewareFunction implements KubeFnHandler, FnContextAware {

    private static final Logger LOG = Logger.getLogger(AuthMiddlewareFunction.class.getName());

    // Simulated JWKS cache — in production, loaded from identity provider
    private static final Set<String> VALID_TOKENS = Set.of("token-valid-user1", "token-valid-admin");

    private FnContext ctx;

    @Override
    public void setContext(FnContext context) {
        this.ctx = context;
    }

    @Override
    public KubeFnResponse handle(KubeFnRequest request) throws Exception {
        String authHeader = request.queryParam("authorization").orElse("");

        // 1. Extract bearer token
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return KubeFnResponse.status(401).body(Map.of(
                    "error", "missing_token",
                    "message", "Authorization header with Bearer token required"
            ));
        }

        String token = authHeader.substring("Bearer ".length());

        // 2. Check local cache first — avoid re-validating recently seen tokens
        String cacheKey = "auth-validated:" + token.hashCode();
        String cached = ctx.cache().get(cacheKey, String.class).orElse(null);
        if (cached != null) {
            LOG.fine("Token validated from cache");
            return KubeFnResponse.ok(Map.of("status", "authenticated", "cached", true));
        }

        // 3. Validate the token (simulated JWT verification)
        TokenClaims claims = validateToken(token);
        if (claims == null) {
            LOG.warning("Invalid token presented");
            return KubeFnResponse.status(401).body(Map.of("error", "invalid_token"));
        }

        // 4. Build AuthContext and publish to heap — every downstream function
        //    reads this zero-copy instead of re-validating the token
        var authContext = new AuthContext(
                claims.userId(), claims.email(), claims.roles(),
                claims.permissions(), token.hashCode(),
                Instant.now().toEpochMilli(),
                Instant.now().toEpochMilli() + 3600_000 // 1 hour expiry
        );

        ctx.heap().publish("auth:" + claims.userId(), authContext);

        // Also publish under a "current request" key for convenience
        ctx.heap().publish("auth:current", authContext);

        // 5. Cache the validation result locally
        ctx.cache().put(cacheKey, "valid");

        LOG.info(String.format("Authenticated user=%s, roles=%s",
                claims.userId(), claims.roles()));

        return KubeFnResponse.ok(Map.of(
                "status", "authenticated",
                "userId", claims.userId(),
                "roles", claims.roles()
        ));
    }

    /** Simulated JWT validation — real impl verifies signature against JWKS. */
    private TokenClaims validateToken(String token) {
        if (!VALID_TOKENS.contains(token)) {
            return null;
        }

        if (token.contains("admin")) {
            return new TokenClaims("user-admin", "admin@example.com",
                    List.of("admin", "user"), List.of("read", "write", "delete"));
        }
        return new TokenClaims("user-001", "user@example.com",
                List.of("user"), List.of("read", "write"));
    }

    public record TokenClaims(String userId, String email,
                              List<String> roles, List<String> permissions) {}

    /** Published to heap — all functions in the organism read this zero-copy. */
    public record AuthContext(String userId, String email, List<String> roles,
                              List<String> permissions, int tokenHash,
                              long authenticatedAt, long expiresAt) {}
}
