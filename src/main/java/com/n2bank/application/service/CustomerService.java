package com.n2bank.application.service;

import com.n2bank.application.port.CustomerRepository;
import com.n2bank.domain.model.Customer;
import java.util.Objects;

/** Coordinates customer use cases. */
public final class CustomerService {
  private final CustomerRepository customers;

  public CustomerService(CustomerRepository customers) {
    this.customers = Objects.requireNonNull(customers, "Customer repository cannot be null");
  }

  /**
   * Creates the customer if its ID is unused, or returns the matching existing customer.
   *
   * @throws IllegalStateException when the ID already belongs to different customer data
   */
  public Customer ensureExists(Customer customer) {
    Objects.requireNonNull(customer, "Customer cannot be null");
    Customer stored = customers.createIfAbsent(customer);
    if (!stored.equals(customer)) {
      throw new IllegalStateException(
          "Customer " + customer.id() + " already exists with different data");
    }
    return stored;
  }
}
