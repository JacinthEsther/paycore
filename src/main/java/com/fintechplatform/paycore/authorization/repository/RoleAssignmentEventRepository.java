package com.fintechplatform.paycore.authorization.repository;

import com.fintechplatform.paycore.authorization.entity.RoleAssignmentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface RoleAssignmentEventRepository
        extends JpaRepository<RoleAssignmentEvent, UUID> {

    List<RoleAssignmentEvent> findByCustomerIdOrderByOccurredAtAscIdAsc(
            UUID customerId
    );
}
