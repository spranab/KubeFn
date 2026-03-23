package com.kubefn.contracts;

import java.util.List;

/**
 * Authentication context — published by auth functions,
 * consumed by any function that needs user identity.
 *
 * <p>Heap key convention: {@code auth:<userId>}
 */
public record AuthContext(
        String userId,
        boolean authenticated,
        String tier,
        List<String> roles,
        List<String> permissions,
        long tokenExpiry,
        String sessionId
) {
    /** Heap key for this user's auth context. */
    public String heapKey() {
        return "auth:" + userId;
    }

    /** Check if user has a specific role. */
    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    /** Check if user has a specific permission. */
    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }

    /** Check if the token is still valid. */
    public boolean isTokenValid() {
        return tokenExpiry > System.currentTimeMillis();
    }
}
