package com.n2bank.application.port;

import com.n2bank.domain.model.Customer;
import java.util.Optional;
import java.util.UUID;

/** Persistence boundary for customers. */
public interface CustomerRepository {
  Optional<Customer> findById(UUID customerId);

  /** Atomically creates the customer when its ID is absent, then returns the stored customer. */
  Customer createIfAbsent(Customer customer);
}
