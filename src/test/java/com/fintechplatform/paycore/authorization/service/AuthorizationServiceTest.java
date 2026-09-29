package com.fintechplatform.paycore.authorization.service;

import com.fintechplatform.paycore.authorization.entity.Permission;
import com.fintechplatform.paycore.authorization.entity.Role;
import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.customer.entity.Customer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorizationServiceTest {

    private final AuthorizationService authorizationService =
            new AuthorizationService();

    private Role customerRole;
    private Role supportRole;
    private Role adminRole;

    @BeforeEach
    void setUp() {

        Permission profileRead = new Permission("PROFILE_READ");
        Permission profileUpdate = new Permission("PROFILE_UPDATE");
        Permission transactionRead = new Permission("TRANSACTION_READ");
        Permission customerRead = new Permission("CUSTOMER_READ");
        Permission customerUpdate = new Permission("CUSTOMER_UPDATE");
        Permission customerSuspend = new Permission("CUSTOMER_SUSPEND");
        Permission customerClose = new Permission("CUSTOMER_CLOSE");

        customerRole = new Role(RoleName.CUSTOMER);
        customerRole.grant(profileRead);
        customerRole.grant(profileUpdate);
        customerRole.grant(transactionRead);

        supportRole = new Role(RoleName.SUPPORT);
        supportRole.grant(customerRead);
        supportRole.grant(customerUpdate);

        adminRole = new Role(RoleName.ADMIN);
        adminRole.grant(customerRead);
        adminRole.grant(customerUpdate);
        adminRole.grant(customerSuspend);
        adminRole.grant(customerClose);
    }

    @Test
    void customerShouldHaveCustomerRole() {

        Customer customer = customerWith(customerRole);

        assertThat(authorizationService.hasRole(customer, RoleName.CUSTOMER))
                .isTrue();

        assertThat(authorizationService.hasRole(customer, RoleName.ADMIN))
                .isFalse();
    }

    @Test
    void customerShouldHaveProfileReadPermission() {

        Customer customer = customerWith(customerRole);

        assertThat(authorizationService.hasPermission(customer, "PROFILE_READ"))
                .isTrue();
    }

    @Test
    void customerShouldNotHaveAdminPermission() {

        Customer customer = customerWith(customerRole);

        assertThat(authorizationService.hasPermission(customer, "CUSTOMER_SUSPEND"))
                .isFalse();

        assertThat(authorizationService.hasPermission(customer, "CUSTOMER_CLOSE"))
                .isFalse();
    }

    @Test
    void adminShouldHaveCustomerSuspendPermission() {

        Customer admin = customerWith(adminRole);

        assertThat(authorizationService.hasPermission(admin, "CUSTOMER_SUSPEND"))
                .isTrue();
    }

    @Test
    void supportShouldNotSuspendCustomers() {

        Customer support = customerWith(supportRole);

        assertThat(authorizationService.hasPermission(support, "CUSTOMER_READ"))
                .isTrue();

        assertThat(authorizationService.hasPermission(support, "CUSTOMER_SUSPEND"))
                .isFalse();
    }

    @Test
    void shouldCombinePermissionsAcrossRoles() {

        Customer customer = customerWith(customerRole);
        customer.assignRole(supportRole);

        assertThat(authorizationService.roleNames(customer))
                .containsExactly(RoleName.CUSTOMER, RoleName.SUPPORT);

        assertThat(authorizationService.permissionNames(customer))
                .containsExactly(
                        "CUSTOMER_READ",
                        "CUSTOMER_UPDATE",
                        "PROFILE_READ",
                        "PROFILE_UPDATE",
                        "TRANSACTION_READ"
                );
    }

    @Test
    void customerWithoutRolesShouldHaveNoPermissions() {

        Customer customer = customerWith();

        assertThat(authorizationService.permissionNames(customer))
                .isEmpty();

        assertThat(authorizationService.hasPermission(customer, "PROFILE_READ"))
                .isFalse();
    }

    private Customer customerWith(Role... roles) {

        Customer customer = Customer.create(
                "Esther",
                "Agboniro",
                "esther@example.com",
                "+2348012345678"
        );

        for (Role role : roles) {
            customer.assignRole(role);
        }

        return customer;
    }
}
