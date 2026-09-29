package com.fintechplatform.paycore.authorization.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import jakarta.persistence.*;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(
        name = "roles",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_roles_name",
                columnNames = "name"
        )
)
public class Role {

    @Id
    @UuidV7
    private UUID id;

    @Column(nullable = false, length = 50)
    private String name;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<Permission> permissions =
            new HashSet<>();

    protected Role() {
    }

    public Role(String name) {
        this.name = name;
    }

    public void grant(Permission permission) {
        permissions.add(permission);
    }

    public void revoke(Permission permission) {
        permissions.remove(permission);
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Set<Permission> getPermissions() {
        return Collections.unmodifiableSet(permissions);
    }
}
