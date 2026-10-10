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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "paycore.demo.enabled", havingValue = "true")
public class DemoAdminGuard implements HandlerInterceptor {

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private static final List<String[]> PROTECTED = List.of(
            new String[]{"PATCH", "/api/v1/customers/{customerId}"},
            new String[]{"DELETE", "/api/v1/customers/{customerId}"},
            new String[]{"POST", "/api/v1/customers/{customerId}/suspend"},
            new String[]{"POST", "/api/v1/admin/customers/{customerId}/roles"},
            new String[]{"POST", "/api/v1/admin/customers/{customerId}/roles/*/revoke"}
    );

    private static final List<String[]> PROTECTED_RECIPIENT_ACCOUNT = List.of(
            new String[]{"POST", "/api/v1/admin/accounts/{accountId}/freeze"},
            new String[]{"POST", "/api/v1/admin/accounts/{accountId}/close"}
    );

    private static final String LOGOUT_ALL = "/api/v1/auth/logout-all";

    private static final String OWN_PROFILE = "/api/v1/customers/me";

    private final DemoProperties properties;
    private final CustomerRepository customerRepository;
    private final DemoRecipientSeeder recipientSeeder;
    private final ObjectMapper objectMapper;

    private volatile Set<UUID> demoStaffIds;

    public DemoAdminGuard(
            DemoProperties properties,
            CustomerRepository customerRepository,
            DemoRecipientSeeder recipientSeeder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.customerRepository = customerRepository;
        this.recipientSeeder = recipientSeeder;
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

        if (targetsDemoRecipient(method, path)) {
            return reject(
                    response,
                    "The shared demo recipient cannot be modified"
            );
        }

        Set<UUID> staffIds = demoStaffIds();

        if (staffIds.isEmpty()) {
            return true;
        }

        UUID caller = currentCustomerId();

        if ("POST".equals(method)
                && LOGOUT_ALL.equals(path)
                && caller != null && staffIds.contains(caller)) {

            return reject(
                    response,
                    "A shared demo staff account cannot sign out every session"
            );
        }

        if ("PATCH".equals(method)
                && OWN_PROFILE.equals(path)
                && caller != null && staffIds.contains(caller)) {

            return reject(
                    response,
                    "A shared demo staff account cannot be modified"
            );
        }

        for (String[] rule : PROTECTED) {

            if (!rule[0].equals(method) || !PATHS.match(rule[1], path)) {
                continue;
            }

            String target =
                    PATHS.extractUriTemplateVariables(rule[1], path)
                            .get("customerId");

            if (staffIds.stream().anyMatch(id -> id.toString().equalsIgnoreCase(target))) {
                return reject(
                        response,
                        "A shared demo staff account cannot be modified"
                );
            }
        }

        return true;
    }

    private boolean targetsDemoRecipient(String method, String path) {

        for (String[] rule : PROTECTED) {
            if (rule[0].equals(method) && PATHS.match(rule[1], path)) {

                String target = PATHS.extractUriTemplateVariables(rule[1], path).get("customerId");

                return customerRepository
                        .findByEmail(DemoRecipientSeeder.EMAIL)
                        .map(recipient -> recipient.getId().toString().equalsIgnoreCase(target))
                        .orElse(false);
            }
        }

        for (String[] rule : PROTECTED_RECIPIENT_ACCOUNT) {
            if (rule[0].equals(method) && PATHS.match(rule[1], path)) {

                String target = PATHS.extractUriTemplateVariables(rule[1], path).get("accountId");

                return recipientSeeder
                        .findAccount()
                        .map(account -> account.getId().toString().equalsIgnoreCase(target))
                        .orElse(false);
            }
        }

        return false;
    }

    private Set<UUID> demoStaffIds() {

        Set<UUID> cached = demoStaffIds;

        if (cached != null) {
            return cached;
        }

        List<String> emails = new ArrayList<>();
        emails.add(properties.normalizedAdminEmail());
        DemoStaff.ALL.forEach(staff -> emails.add(staff.email()));

        Set<UUID> found = new HashSet<>();

        for (String email : emails) {
            customerRepository.findByEmail(email).map(Customer::getId).ifPresent(found::add);
        }

        if (found.size() == emails.size()) {
            demoStaffIds = Set.copyOf(found);
        }

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
