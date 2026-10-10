package com.fintechplatform.paycore.authorization.service;

import com.fintechplatform.paycore.authorization.dto.CustomerRolesResponse;
import com.fintechplatform.paycore.authorization.dto.RoleAssignmentEventResponse;
import com.fintechplatform.paycore.authorization.entity.Role;
import com.fintechplatform.paycore.authorization.entity.RoleAssignmentEvent;
import com.fintechplatform.paycore.authorization.entity.RoleName;
import com.fintechplatform.paycore.authorization.exception.ConflictingRoleException;
import com.fintechplatform.paycore.authorization.exception.RoleAlreadyAssignedException;
import com.fintechplatform.paycore.authorization.exception.RoleNotAssignedException;
import com.fintechplatform.paycore.authorization.exception.RoleNotFoundException;
import com.fintechplatform.paycore.authorization.repository.RoleAssignmentEventRepository;
import com.fintechplatform.paycore.authorization.repository.RoleRepository;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class RoleAssignmentService {

    private final RoleRepository roleRepository;
    private final RoleAssignmentEventRepository eventRepository;
    private final CustomerRepository customerRepository;

    public RoleAssignmentService(
            RoleRepository roleRepository,
            RoleAssignmentEventRepository eventRepository,
            CustomerRepository customerRepository
    ) {
        this.roleRepository = roleRepository;
        this.eventRepository = eventRepository;
        this.customerRepository = customerRepository;
    }

    @Transactional(readOnly = true)
    public CustomerRolesResponse getRoles(UUID customerId) {

        return toResponse(findCustomer(customerId));
    }

    @Transactional
    public CustomerRolesResponse assignRole(
            UUID customerId,
            String roleName,
            UUID adminId,
            String reason
    ) {

        ensureNotSelf(customerId, adminId);

        Customer customer = findCustomer(customerId);

        assignRole(customer, normalize(roleName), adminId, reason.trim());

        return toResponse(customer);
    }

    @Transactional
    public CustomerRolesResponse revokeRole(
            UUID customerId,
            String roleName,
            UUID adminId,
            String reason
    ) {

        ensureNotSelf(customerId, adminId);

        Customer customer = findCustomer(customerId);

        revokeRole(customer, normalize(roleName), adminId, reason.trim());

        return toResponse(customer);
    }

    @Transactional(readOnly = true)
    public List<RoleAssignmentEventResponse> getHistory(UUID customerId) {

        findCustomer(customerId);

        List<RoleAssignmentEvent> events = history(customerId);

        Map<UUID, String> roleNames =
                roleRepository
                        .findAllById(
                                events.stream()
                                        .map(RoleAssignmentEvent::getRoleId)
                                        .distinct()
                                        .toList()
                        )
                        .stream()
                        .collect(Collectors.toMap(
                                Role::getId,
                                Role::getName
                        ));

        return events.stream()
                .map(event -> new RoleAssignmentEventResponse(
                        event.getId(),
                        roleNames.get(event.getRoleId()),
                        event.getAction(),
                        event.getPerformedBy(),
                        event.getReason(),
                        event.getOccurredAt()
                ))
                .toList();
    }

    @Transactional
    public void assignRole(
            Customer customer,
            String roleName,
            UUID performedBy,
            String reason
    ) {

        Role role = findRole(roleName);

        if (customer.getRoles().contains(role)) {
            throw new RoleAlreadyAssignedException(roleName);
        }

        customer.getRoles()
                .stream()
                .map(Role::getName)
                .filter(RoleName.conflictsWith(roleName)::contains)
                .findFirst()
                .ifPresent(held -> {
                    throw new ConflictingRoleException(roleName, held);
                });

        customer.assignRole(role);

        eventRepository.save(
                RoleAssignmentEvent.assigned(
                        customer.getId(),
                        role.getId(),
                        performedBy,
                        reason
                )
        );
    }

    @Transactional
    public void revokeRole(
            Customer customer,
            String roleName,
            UUID performedBy,
            String reason
    ) {

        Role role = findRole(roleName);

        if (!customer.getRoles().contains(role)) {
            throw new RoleNotAssignedException(roleName);
        }

        customer.removeRole(role);

        eventRepository.save(
                RoleAssignmentEvent.revoked(
                        customer.getId(),
                        role.getId(),
                        performedBy,
                        reason
                )
        );
    }

    @Transactional(readOnly = true)
    public List<RoleAssignmentEvent> history(UUID customerId) {

        return eventRepository
                .findByCustomerIdOrderByOccurredAtAscIdAsc(customerId);
    }

    private void ensureNotSelf(UUID customerId, UUID adminId) {

        if (customerId.equals(adminId)) {
            throw new AccessDeniedException(
                    "Admins cannot change their own roles"
            );
        }
    }

    private Customer findCustomer(UUID customerId) {

        return customerRepository
                .findById(customerId)
                .orElseThrow(() -> new CustomerNotFoundException(customerId));
    }

    private Role findRole(String roleName) {

        return roleRepository
                .findByName(roleName)
                .orElseThrow(() -> new RoleNotFoundException(roleName));
    }

    private String normalize(String roleName) {
        return roleName.trim().toUpperCase(Locale.ROOT);
    }

    private CustomerRolesResponse toResponse(Customer customer) {

        return new CustomerRolesResponse(
                customer.getId(),
                customer.getRoles()
                        .stream()
                        .map(Role::getName)
                        .sorted()
                        .toList()
        );
    }
}
