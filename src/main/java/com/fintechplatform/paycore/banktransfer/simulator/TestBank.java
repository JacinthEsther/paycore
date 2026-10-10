package com.fintechplatform.paycore.banktransfer.simulator;

import com.fintechplatform.paycore.banktransfer.rail.Bank;
import com.fintechplatform.paycore.customer.entity.Customer;
import com.fintechplatform.paycore.customer.repository.CustomerRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "paycore.rails.provider", havingValue = "simulated")
public class TestBank {

    public static final Bank BANK = new Bank("999001", "Test Bank");

    private static final List<String> OTHER_HOLDERS = List.of(
            "Chiamaka Obi", "Ibrahim Musa", "Funke Adeyemi", "Emeka Nwosu", "Aisha Bello",
            "Tobi Ogunleye", "Ngozi Eze", "Yusuf Abdullahi", "Kemi Balogun", "Chinedu Okeke"
    );

    private final CustomerRepository customerRepository;

    public TestBank(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    public String accountNumberFor(UUID customerId) {
        return "7" + digits(customerId.toString(), 9);
    }

    public Optional<String> holderNameFor(UUID customerId) {

        return customerRepository
                .findById(customerId)
                .map(TestBank::fullName);
    }

    public Optional<String> holderOf(String accountNumber, UUID askingCustomerId) {

        if (accountNumber == null || !accountNumber.matches("\\d{10}") || accountNumber.startsWith("000")) {
            return Optional.empty();
        }

        if (askingCustomerId != null && accountNumber.equals(accountNumberFor(askingCustomerId))) {
            return holderNameFor(askingCustomerId);
        }

        int index = Integer.parseInt(digits(accountNumber, 4)) % OTHER_HOLDERS.size();
        return Optional.of(OTHER_HOLDERS.get(index));
    }

    static String fullName(Customer customer) {
        return customer.getFirstName() + " " + customer.getLastName();
    }

    private static String digits(String input, int count) {

        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(count);

            for (int i = 0; out.length() < count; i++) {
                out.append(Math.floorMod(hash[i % hash.length], 10));
            }

            return out.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
