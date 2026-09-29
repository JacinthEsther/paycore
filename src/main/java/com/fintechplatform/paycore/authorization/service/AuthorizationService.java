package com.fintechplatform.paycore.authorization.service;

import com.fintechplatform.paycore.authorization.entity.Permission;
import com.fintechplatform.paycore.authorization.entity.Role;
import com.fintechplatform.paycore.customer.entity.Customer;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

@Service
public class AuthorizationService {

    public boolean hasRole(
            Customer customer,
            String role
    ) {

        return customer.getRoles()
                .stream()
                .anyMatch(
                        value ->
                                value.getName()
                                        .equals(role)
                );
    }

    public boolean hasPermission(
            Customer customer,
            String permission
    ) {

        return customer.getRoles()
                .stream()
                .flatMap(
                        role ->
                                role.getPermissions()
                                        .stream()
                )
                .anyMatch(
                        value ->
                                value.getName()
                                        .equals(permission)
                );
    }

    public Set<String> roleNames(Customer customer) {

        return customer.getRoles()
                .stream()
                .map(Role::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    public Set<String> permissionNames(Customer customer) {

        return customer.getRoles()
                .stream()
                .flatMap(
                        role ->
                                role.getPermissions()
                                        .stream()
                )
                .map(Permission::getName)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
