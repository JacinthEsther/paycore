package com.fintechplatform.paycore.authorization.entity;

import com.fintechplatform.paycore.common.persistence.UuidV7;
import jakarta.persistence.*;

import java.util.UUID;

@Entity
@Table(
        name = "permissions",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_permissions_name",
                columnNames = "name"
        )
)
public class Permission {

    @Id
    @UuidV7
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    protected Permission() {
    }

    public Permission(String name) {
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }
}
