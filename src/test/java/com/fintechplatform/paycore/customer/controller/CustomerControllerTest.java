package com.fintechplatform.paycore.customer.controller;

import com.fintechplatform.paycore.customer.dto.request.RegisterCustomerRequest;
import com.fintechplatform.paycore.customer.dto.request.UpdateCustomerRequest;
import com.fintechplatform.paycore.customer.dto.response.CustomerResponse;
import com.fintechplatform.paycore.customer.enums.CustomerStatus;
import com.fintechplatform.paycore.customer.exception.CustomerNotFoundException;
import com.fintechplatform.paycore.customer.exception.DuplicateCustomerException;
import com.fintechplatform.paycore.customer.service.CustomerService;
import com.fintechplatform.paycore.identity.repository.LoginSessionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(CustomerController.class)
@AutoConfigureMockMvc(addFilters = false)
class CustomerControllerTest {


    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CustomerService customerService;

    // Needed by CurrentUserJwtAuthenticationConverter, which this slice loads.
    @MockitoBean
    private LoginSessionRepository loginSessionRepository;

    @Test
    void shouldReturnCustomerProfile() throws Exception {

        UUID customerId = UUID.randomUUID();

        CustomerResponse response =
                new CustomerResponse(
                        customerId,
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "+2348012345678",
                        CustomerStatus.ACTIVE,
                        true,
                        true,
                        Instant.now(),
                        Instant.now()
                );

        when(customerService.getCustomer(customerId))
                .thenReturn(response);

        mockMvc.perform(
                        get("/api/v1/customers/{customerId}",
                                customerId)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id")
                        .value(customerId.toString()))
                .andExpect(jsonPath("$.firstName")
                        .value("Esther"))
                .andExpect(jsonPath("$.email")
                        .value("esther@example.com"))
                .andExpect(jsonPath("$.status")
                        .value("ACTIVE"));
    }

    @Test
    void shouldUpdateCustomerProfile() throws Exception {

        UUID customerId = UUID.randomUUID();

        CustomerResponse response =
                new CustomerResponse(
                        customerId,
                        "Jane",
                        "Smith",
                        "jane@example.com",
                        "+2348012345678",
                        CustomerStatus.ACTIVE,
                        true,
                        true,
                        Instant.now(),
                        Instant.now()
                );

        when(customerService.updateProfile(
                eq(customerId),
                any(UpdateCustomerRequest.class)
        )).thenReturn(response);

        mockMvc.perform(
                        patch("/api/v1/customers/{customerId}",
                                customerId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "firstName": "Jane",
                                          "lastName": "Smith"
                                        }
                                        """)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.firstName")
                        .value("Jane"))
                .andExpect(jsonPath("$.lastName")
                        .value("Smith"))
                .andExpect(jsonPath("$.email")
                        .value("jane@example.com"));
    }

    @Test
    void shouldSuspendCustomer() throws Exception {

        UUID customerId = UUID.randomUUID();

        CustomerResponse response =
                new CustomerResponse(
                        customerId,
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "+2348012345678",
                        CustomerStatus.SUSPENDED,
                        true,
                        true,
                        Instant.now(),
                        Instant.now()
                );

        when(customerService.suspendCustomer(customerId))
                .thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/customers/{customerId}/suspend",
                                customerId)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("SUSPENDED"));
    }

    @Test
    void shouldReactivateCustomer() throws Exception {

        UUID customerId = UUID.randomUUID();

        CustomerResponse response =
                new CustomerResponse(
                        customerId,
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "+2348012345678",
                        CustomerStatus.ACTIVE,
                        true,
                        true,
                        Instant.now(),
                        Instant.now()
                );

        when(customerService.reactivateCustomer(customerId))
                .thenReturn(response);

        mockMvc.perform(
                        post("/api/v1/customers/{customerId}/reactivate",
                                customerId)
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status")
                        .value("ACTIVE"));
    }

    @Test
    void shouldCloseCustomerAccount() throws Exception {

        UUID customerId = UUID.randomUUID();

        doNothing()
                .when(customerService)
                .closeCustomer(customerId);

        mockMvc.perform(
                        delete("/api/v1/customers/{customerId}",
                                customerId)
                )
                .andExpect(status().isNoContent());

        verify(customerService)
                .closeCustomer(customerId);
    }

    @Test
    void shouldRejectInvalidCustomerId() throws Exception {

        mockMvc.perform(
                        get("/api/v1/customers/not-a-uuid")
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(customerService);
    }

    @Test
    void shouldReturn404WhenCustomerDoesNotExist() throws Exception {

        UUID customerId = UUID.randomUUID();

        when(customerService.getCustomer(customerId))
                .thenThrow(
                        new CustomerNotFoundException(customerId)
                );

        mockMvc.perform(
                        get("/api/v1/customers/{customerId}",
                                customerId)
                )
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error")
                        .value("CUSTOMER_NOT_FOUND"));
    }

    @Test
    void shouldReturn409WhenEmailAlreadyExists() throws Exception {

        RegisterCustomerRequest request =
                new RegisterCustomerRequest(
                        "Esther",
                        "Agboniro",
                        "esther@example.com",
                        "NG",
                        "08012345678",
                        "Password123"
                );

        when(customerService.register(any(RegisterCustomerRequest.class)))
                .thenThrow(
                        new DuplicateCustomerException(
                                "Email is already registered"
                        )
                );

        mockMvc.perform(
                        post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "firstName": "Esther",
                                          "lastName": "Agboniro",
                                          "email": "esther@example.com",
                                          "countryCode": "NG",
                                          "phoneNumber": "08012345678",
                                          "password": "Password123"
                                        }
                                        """)
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error")
                        .value("CUSTOMER_ALREADY_EXISTS"));
    }

    @Test
    void shouldRejectInvalidRegistrationRequest() throws Exception {

        mockMvc.perform(
                        post("/api/v1/customers")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "firstName": "",
                                          "lastName": "",
                                          "email": "not-an-email",
                                          "countryCode": "NG",
                                          "phoneNumber": "",
                                          "password": "123"
                                        }
                                        """)
                )
                .andExpect(status().isBadRequest());

        verifyNoInteractions(customerService);
    }
}
