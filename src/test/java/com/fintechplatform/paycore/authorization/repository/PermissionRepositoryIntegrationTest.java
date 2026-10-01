package com.fintechplatform.paycore.authorization.repository;

import com.fintechplatform.paycore.authorization.entity.Permission;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@SpringBootTest
@Transactional
class PermissionRepositoryIntegrationTest {

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
    private PermissionRepository permissionRepository;

    @Test
    void shouldSeedAllPermissions() {

        assertThat(permissionRepository.findAll())
                .extracting(Permission::getName)
                .containsExactlyInAnyOrder(
                        "PROFILE_READ",
                        "PROFILE_UPDATE",
                        "TRANSACTION_READ",
                        "CUSTOMER_READ",
                        "CUSTOMER_UPDATE",
                        "CUSTOMER_SUSPEND",
                        "CUSTOMER_CLOSE",
                        "KYC_REVIEW",
                        "ROLE_MANAGE",
                        "KYC_READ",
                        "KYC_SUBMIT",
                        "ACCOUNT_READ",
                        "TRANSACTION_CREATE",
                        "ACCOUNT_OPEN",
                        "ACCOUNT_VIEW_ALL",
                        "ACCOUNT_MANAGE",
                        "LEDGER_REQUEST",
                        "LEDGER_APPROVE"
                );
    }

    @Test
    void seededPermissionsShouldHaveUuidV7Ids() {

        assertThat(permissionRepository.findAll())
                .isNotEmpty()
                .allSatisfy(permission ->
                        assertThat(permission.getId().version()).isEqualTo(7)
                );
    }

    @Test
    void shouldFindPermissionByName() {

        assertThat(permissionRepository.findByName("PROFILE_READ"))
                .isPresent()
                .get()
                .extracting(Permission::getId)
                .isNotNull();
    }

    @Test
    void shouldSaveNewPermission() {

        Permission saved =
                permissionRepository.saveAndFlush(
                        new Permission("ACCOUNT_CLOSE")
                );

        assertThat(saved.getId().version()).isEqualTo(7);

        assertThat(permissionRepository.findByName("ACCOUNT_CLOSE"))
                .isPresent();
    }

    @Test
    void shouldEnforceUniquePermissionName() {

        assertThatThrownBy(() ->
                permissionRepository.saveAndFlush(
                        new Permission("PROFILE_READ")
                )
        )
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
