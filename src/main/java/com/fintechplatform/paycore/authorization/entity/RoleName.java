package com.fintechplatform.paycore.authorization.entity;

import java.util.Map;
import java.util.Set;

public final class RoleName {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String SUPPORT = "SUPPORT";
    public static final String COMPLIANCE = "COMPLIANCE";
    public static final String OPERATIONS_OFFICER = "OPERATIONS_OFFICER";
    public static final String OPERATIONS_SUPERVISOR = "OPERATIONS_SUPERVISOR";
    public static final String ADMIN = "ADMIN";

    public static final Set<String> STAFF =
            Set.of(SUPPORT, COMPLIANCE, OPERATIONS_OFFICER, OPERATIONS_SUPERVISOR, ADMIN);

    private static final Map<String, Set<String>> CONFLICTS = Map.of(
            OPERATIONS_OFFICER, Set.of(OPERATIONS_SUPERVISOR, ADMIN),
            OPERATIONS_SUPERVISOR, Set.of(OPERATIONS_OFFICER, ADMIN),
            COMPLIANCE, Set.of(ADMIN),
            SUPPORT, Set.of(ADMIN),
            ADMIN, Set.of(SUPPORT, COMPLIANCE, OPERATIONS_OFFICER, OPERATIONS_SUPERVISOR)
    );

    private RoleName() {
    }

    public static Set<String> conflictsWith(String role) {
        return CONFLICTS.getOrDefault(role, Set.of());
    }
}
