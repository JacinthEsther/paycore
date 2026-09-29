package com.fintechplatform.paycore.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import com.fintechplatform.paycore.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The demo admin is shared by every visitor of the public preview, so no
 * visitor may lock the others out of it: it cannot be edited, suspended,
 * closed or have its roles changed, and nobody can end every session of
 * it at once. Admin actions on any other customer work normally.
 */
@Component
@ConditionalOnProperty(name = "paycore.demo.enabled", havingValue = "true")
public class DemoAdminGuard implements HandlerInterceptor {

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    /**
     * Method and path pattern of every request that changes the customer
     * named by {customerId}.
     */
    private static final List<String[]> PROTECTED = List.of(
            new String[]{"PATCH", "/api/v1/customers/{customerId}"},
            new String[]{"DELETE", "/api/v1/customers/{customerId}"},
            new String[]{"POST", "/api/v1/customers/{customerId}/suspend"},
            new String[]{"POST", "/api/v1/admin/customers/{customerId}/roles"},
            new String[]{"POST", "/api/v1/admin/customers/{customerId}/roles/*/revoke"}
    );

    private static final String LOGOUT_ALL = "/api/v1/auth/logout-all";

    private final DemoProperties properties;
    private final CustomerRepository customerRepository;
    private final ObjectMapper objectMapper;

    private volatile UUID demoAdminId;

    public DemoAdminGuard(
            DemoProperties properties,
            CustomerRepository customerRepository,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.customerRepository = customerRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(
            HttpServletRequest request,
            HttpServletResponse response,
            Object handler
    ) throws IOException {

        String path = request.getRequestURI();
        String method = request.getMethod();

        Optional<UUID> adminId = demoAdminId();

        if (adminId.isEmpty()) {
            return true;
        }

        if ("POST".equals(method)
                && LOGOUT_ALL.equals(path)
                && adminId.get().equals(currentCustomerId())) {

            return reject(
                    response,
                    "The shared demo admin cannot sign out every session"
            );
        }

        for (String[] rule : PROTECTED) {

            if (!rule[0].equals(method) || !PATHS.match(rule[1], path)) {
                continue;
            }

            String target =
                    PATHS.extractUriTemplateVariables(rule[1], path)
                            .get("customerId");

            if (adminId.get().toString().equalsIgnoreCase(target)) {
                return reject(
                        response,
                        "The shared demo admin account cannot be modified"
                );
            }
        }

        return true;
    }

    private Optional<UUID> demoAdminId() {

        UUID cached = demoAdminId;

        if (cached != null) {
            return Optional.of(cached);
        }

        Optional<UUID> found =
                customerRepository
                        .findByEmail(properties.normalizedAdminEmail())
                        .map(Customer::getId);

        found.ifPresent(id -> demoAdminId = id);

        return found;
    }

    private UUID currentCustomerId() {

        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();

        if (authentication != null
                && authentication.getPrincipal() instanceof CurrentUser user) {
            return user.customerId();
        }

        return null;
    }

    private boolean reject(
            HttpServletResponse response,
            String message
    ) throws IOException {

        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);

        objectMapper.writeValue(
                response.getOutputStream(),
                Map.of(
                        "timestamp", Instant.now().toString(),
                        "status", 403,
                        "error", "DEMO_ACCOUNT_PROTECTED",
                        "message", message
                )
        );

        return false;
    }
}
