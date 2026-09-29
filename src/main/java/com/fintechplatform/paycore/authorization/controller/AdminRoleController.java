package com.fintechplatform.paycore.authorization.controller;

import com.fintechplatform.paycore.authorization.dto.AssignRoleRequest;
import com.fintechplatform.paycore.authorization.dto.CustomerRolesResponse;
import com.fintechplatform.paycore.authorization.dto.RevokeRoleRequest;
import com.fintechplatform.paycore.authorization.dto.RoleAssignmentEventResponse;
import com.fintechplatform.paycore.authorization.service.RoleAssignmentService;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Admin-only role management. Every change needs a reason and is written
 * to the role assignment audit trail with the acting admin's id. Admins
 * cannot change their own roles.
 */
@RestController
@RequestMapping("/api/v1/admin/customers/{customerId}/roles")
@PreAuthorize("hasAuthority('ROLE_MANAGE')")
public class AdminRoleController {

    private final RoleAssignmentService roleAssignmentService;

    public AdminRoleController(
            RoleAssignmentService roleAssignmentService
    ) {
        this.roleAssignmentService = roleAssignmentService;
    }

    @GetMapping
    public CustomerRolesResponse getRoles(
            @PathVariable UUID customerId
    ) {

        return roleAssignmentService.getRoles(customerId);
    }

    @PostMapping
    public CustomerRolesResponse assignRole(
            @PathVariable UUID customerId,
            @AuthenticationPrincipal CurrentUser admin,
            @Valid @RequestBody AssignRoleRequest request
    ) {

        return roleAssignmentService.assignRole(
                customerId,
                request.role(),
                admin.customerId(),
                request.reason()
        );
    }

    /**
     * POST rather than DELETE because the reason travels in the body, and
     * DELETE bodies are dropped by some clients and proxies.
     */
    @PostMapping("/{roleName}/revoke")
    public CustomerRolesResponse revokeRole(
            @PathVariable UUID customerId,
            @PathVariable String roleName,
            @AuthenticationPrincipal CurrentUser admin,
            @Valid @RequestBody RevokeRoleRequest request
    ) {

        return roleAssignmentService.revokeRole(
                customerId,
                roleName,
                admin.customerId(),
                request.reason()
        );
    }

    @GetMapping("/history")
    public List<RoleAssignmentEventResponse> getHistory(
            @PathVariable UUID customerId
    ) {

        return roleAssignmentService.getHistory(customerId);
    }
}
