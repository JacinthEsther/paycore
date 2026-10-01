package com.fintechplatform.paycore.authorization.repository;

import com.fintechplatform.paycore.authorization.entity.Permission;
import com.fintechplatform.paycore.authorization.entity.Role;
import com.fintechplatform.paycore.authorization.entity.RoleName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@Transactional
class RoleRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:18")
                    .withDatabaseName("paycore")
                    .withUsername("postgres")
                    .withPassword("postgres");

    @DynamicPropertySource
    static void configureProperties(
            DynamicPropertyRegistry registry
    ) {
        registry.add(
                "spring.datasource.url",
                postgres::getJdbcUrl
        );

        registry.add(
                "spring.datasource.username",
                postgres::getUsername
        );

        registry.add(
                "spring.datasource.password",
                postgres::getPassword
        );
    }

    @Autowired
    private RoleRepository roleRepository;

    @Test
    void shouldSeedAllRoles() {

        assertThat(roleRepository.findAll())
                .extracting(Role::getName)
                .contains(
                        RoleName.CUSTOMER,
                        RoleName.SUPPORT,
                        RoleName.ADMIN,
                        RoleName.OPERATIONS
                );
    }

    @Test
    void seededRolesShouldHaveUuidV7Ids() {

        assertThat(roleRepository.findAll())
                .isNotEmpty()
                .allSatisfy(role ->
                        assertThat(role.getId().version()).isEqualTo(7)
                );
    }

    @Test
    void shouldSeedCustomerRolePermissions() {

        assertThat(permissionsOf(RoleName.CUSTOMER))
                .containsExactlyInAnyOrder(
                        "PROFILE_READ",
                        "PROFILE_UPDATE",
                        "TRANSACTION_READ",
                        "TRANSACTION_CREATE",
                        "KYC_READ",
                        "KYC_SUBMIT",
                        "ACCOUNT_READ",
                        "ACCOUNT_OPEN"
                );
    }

    @Test
    void shouldSeedSupportRolePermissions() {

        assertThat(permissionsOf(RoleName.SUPPORT))
                .containsExactlyInAnyOrder(
                        "CUSTOMER_READ",
                        "CUSTOMER_UPDATE",
                        "ACCOUNT_VIEW_ALL"
                );
    }

    @Test
    void shouldSeedAdminRolePermissions() {

        assertThat(permissionsOf(RoleName.ADMIN))
                .containsExactlyInAnyOrder(
                        "CUSTOMER_READ",
                        "CUSTOMER_UPDATE",
                        "CUSTOMER_SUSPEND",
                        "CUSTOMER_CLOSE",
                        "KYC_REVIEW",
                        "ROLE_MANAGE",
                        "ACCOUNT_VIEW_ALL",
                        "ACCOUNT_MANAGE"
                );
    }

    /**
     * Operations officers request and approve money corrections; nobody
     * else touches money, and they cannot manage customers or roles.
     */
    @Test
    void shouldSeedOperationsRolePermissions() {

        assertThat(permissionsOf(RoleName.OPERATIONS))
                .containsExactlyInAnyOrder(
                        "CUSTOMER_READ",
                        "ACCOUNT_VIEW_ALL",
                        "LEDGER_REQUEST",
                        "LEDGER_APPROVE"
                );

        for (String role : List.of(RoleName.CUSTOMER, RoleName.SUPPORT, RoleName.ADMIN)) {
            assertThat(permissionsOf(role)).doesNotContain("LEDGER_REQUEST", "LEDGER_APPROVE");
        }
    }

    @Test
    void onlyAdminShouldReviewKyc() {

        assertThat(permissionsOf(RoleName.CUSTOMER))
                .doesNotContain("KYC_REVIEW");

        assertThat(permissionsOf(RoleName.SUPPORT))
                .doesNotContain("KYC_REVIEW");
    }

    @Test
    void shouldEnforceUniqueRoleName() {

        assertThatThrownBy(() ->
                roleRepository.saveAndFlush(new Role(RoleName.ADMIN))
        )
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private java.util.List<String> permissionsOf(String roleName) {

        return roleRepository.findByName(roleName)
                .orElseThrow()
                .getPermissions()
                .stream()
                .map(Permission::getName)
                .toList();
    }
}
