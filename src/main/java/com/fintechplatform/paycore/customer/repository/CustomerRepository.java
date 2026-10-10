package com.fintechplatform.paycore.customer.repository;

import com.fintechplatform.paycore.customer.entity.Customer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface CustomerRepository
        extends JpaRepository<Customer, UUID> {

    Optional<Customer> findByEmail(String email);

    @Query("""
            select c from Customer c
            left join fetch c.roles r
            left join fetch r.permissions
            where c.email = :email
            """)
    Optional<Customer> findWithAuthoritiesByEmail(@Param("email") String email);

    @Query("""
            select c from Customer c
            left join fetch c.roles r
            left join fetch r.permissions
            where c.id = :id
            """)
    Optional<Customer> findWithAuthoritiesById(@Param("id") UUID id);

    Optional<Customer> findByPhoneNumber(String phoneNumber);

    boolean existsByEmail(String email);

    boolean existsByPhoneNumber(String phoneNumber);

    Page<Customer> findByEmailContainingIgnoreCaseOrFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(
            String email,
            String firstName,
            String lastName,
            Pageable pageable
    );
}
