package com.fintechplatform.paycore.authorization.entity;

/**
 * Role names seeded by V6__create_roles_and_permissions.sql.
 */
public final class RoleName {

    public static final String CUSTOMER = "CUSTOMER";
    public static final String SUPPORT = "SUPPORT";
    public static final String ADMIN = "ADMIN";
    /** Added by V24: requests and approves money corrections (maker-checker). */
    public static final String OPERATIONS = "OPERATIONS";

    private RoleName() {
    }
}
