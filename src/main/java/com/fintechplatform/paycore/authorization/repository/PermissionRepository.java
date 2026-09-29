package com.fintechplatform.paycore.authorization.repository;

import com.fintechplatform.paycore.authorization.entity.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PermissionRepository
        extends JpaRepository<Permission, UUID> {

    Optional<Permission> findByName(String name);
}
