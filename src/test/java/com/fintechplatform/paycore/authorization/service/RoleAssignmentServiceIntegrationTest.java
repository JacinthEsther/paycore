package com.fintechplatform.paycore.authorization.service;

import com.fintechplatform.paycore.authorization.entity.Role;
import com.fintechplatform.paycore.authorization.entity.RoleAssignmentEvent;
import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.authorization.enums.RoleAssignmentAction;
import com.fintechplatform.paycore.authorization.exception.RoleAlreadyAssignedException;
import com.fintechplatform.paycore.authorization.exception.RoleNotAssignedException;
import com.fintechplatform.paycore.authorization.exception.RoleNotFoundException;
import com.fintechplatform.paycore.authorization.repository.RoleRepository;
import com.fintechplatform.paycore.customer.dto.request.RegisterCustomerRequest;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.customer.service.CustomerService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@Testcontainers
@SpringBootTest
@Transactional
class RoleAssignmentServiceIntegrationTest {

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
    private RoleAssignmentService roleAssignmentService;

    @Autowired
    private CustomerService customerService;

    @Autowired
    private CustomerRepository customerRepository;

    @Autowired
    private RoleRepository roleRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void registrationShouldRecordSystemAssignedCustomerRole() {

        UUID customerId =
                customerService.register(
                        new RegisterCustomerRequest(
                                "Esther",
                                "Agboniro",
                                "register@example.com",
                                "NG",
                                "08011111111",
                                "Password123!"
                        )
                ).id();

        entityManager.flush();

        List<RoleAssignmentEvent> history =
                roleAssignmentService.history(customerId);

        assertThat(history).singleElement().satisfies(event -> {
            assertThat(event.getAction())
                    .isEqualTo(RoleAssignmentAction.ASSIGNED);
            assertThat(event.getRoleId())
                    .isEqualTo(role(RoleName.CUSTOMER).getId());
            assertThat(event.getPerformedBy()).isNull();
            assertThat(event.getReason())
                    .isEqualTo("Default role on registration");
            assertThat(event.getId().version()).isEqualTo(7);
        });
    }

    @Test
    void shouldRecordWhoAssignedRoleAndWhen() {

        Customer admin = customer("admin@example.com", "+2348011111112");
        Customer target = customer("target@example.com", "+2348011111113");

        roleAssignmentService.assignRole(
                target,
                RoleName.SUPPORT,
                admin.getId(),
                "Joined support team"
        );

        entityManager.flush();

        assertThat(target.getRoles())
                .extracting(Role::getName)
                .containsExactly(RoleName.SUPPORT);

        assertThat(roleAssignmentService.history(target.getId()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.getAction())
                            .isEqualTo(RoleAssignmentAction.ASSIGNED);
                    assertThat(event.getPerformedBy())
                            .isEqualTo(admin.getId());
                    assertThat(event.getReason())
                            .isEqualTo("Joined support team");
                    assertThat(event.getOccurredAt()).isNotNull();
                });

        Timestamp assignedAt =
                jdbcTemplate.queryForObject(
                        "SELECT assigned_at FROM customer_roles "
                                + "WHERE customer_id = ?",
                        Timestamp.class,
                        target.getId()
                );

        assertThat(assignedAt).isNotNull();
    }

    @Test
    void shouldRejectDuplicateAssignmentWithoutRecordingEvent() {

        Customer target = customer("dup@example.com", "+2348011111114");

        roleAssignmentService.assignRole(
                target, RoleName.ADMIN, null, "First grant"
        );

        assertThatThrownBy(() ->
                roleAssignmentService.assignRole(
                        target, RoleName.ADMIN, null, "Second grant"
                )
        )
                .isInstanceOf(RoleAlreadyAssignedException.class);

        entityManager.flush();

        assertThat(roleAssignmentService.history(target.getId()))
                .hasSize(1);
    }

    @Test
    void revocationShouldRemoveRoleAndKeepFullHistory() {

        Customer admin = customer("revoker@example.com", "+2348011111115");
        Customer target = customer("revoked@example.com", "+2348011111116");

        roleAssignmentService.assignRole(
                target, RoleName.SUPPORT, admin.getId(), "Temporary cover"
        );

        roleAssignmentService.revokeRole(
                target, RoleName.SUPPORT, admin.getId(), "Cover ended"
        );

        entityManager.flush();

        assertThat(target.getRoles()).isEmpty();

        Integer currentAssignments =
                jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM customer_roles "
                                + "WHERE customer_id = ?",
                        Integer.class,
                        target.getId()
                );

        assertThat(currentAssignments).isZero();

        assertThat(roleAssignmentService.history(target.getId()))
                .extracting(
                        RoleAssignmentEvent::getAction,
                        RoleAssignmentEvent::getReason
                )
                .containsExactly(
                        tuple(
                                RoleAssignmentAction.ASSIGNED,
                                "Temporary cover"
                        ),
                        tuple(
                                RoleAssignmentAction.REVOKED,
                                "Cover ended"
                        )
                );
    }

    @Test
    void shouldRejectRevokingRoleCustomerDoesNotHave() {

        Customer target = customer("none@example.com", "+2348011111117");

        assertThatThrownBy(() ->
                roleAssignmentService.revokeRole(
                        target, RoleName.ADMIN, null, "Nothing to revoke"
                )
        )
                .isInstanceOf(RoleNotAssignedException.class)
                .hasMessageContaining("does not have role");

        assertThat(roleAssignmentService.history(target.getId()))
                .isEmpty();
    }

    @Test
    void shouldRejectUnknownRole() {

        Customer target = customer("unknown@example.com", "+2348011111118");

        assertThatThrownBy(() ->
                roleAssignmentService.assignRole(
                        target, "SUPERUSER", null, "No such role"
                )
        )
                .isInstanceOf(RoleNotFoundException.class)
                .hasMessageContaining("SUPERUSER");
    }

    private Customer customer(String email, String phone) {

        return customerRepository.saveAndFlush(
                Customer.create("Esther", "Agboniro", email, phone)
        );
    }

    private Role role(String name) {
        return roleRepository.findByName(name).orElseThrow();
    }
}
