package com.n2bank.application.service;

import com.n2bank.application.fee.FeeContext;
import com.n2bank.application.fee.FeePolicy;
import com.n2bank.domain.model.FeeQuote;
import com.n2bank.domain.model.FeeType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Selects the configured strategy for a fee type. It does not persist or post fees. */
public final class FeeService {
  private final Map<FeeType, FeePolicy> policies;

  public FeeService(List<FeePolicy> policies) {
    Objects.requireNonNull(policies, "Fee policies cannot be null");
    EnumMap<FeeType, FeePolicy> configured = new EnumMap<>(FeeType.class);
    for (FeePolicy policy : policies) {
      Objects.requireNonNull(policy, "Fee policy cannot be null");
      FeePolicy previous = configured.putIfAbsent(policy.type(), policy);
      if (previous != null) {
        throw new IllegalArgumentException("Multiple policies configured for " + policy.type());
      }
    }
    this.policies = Map.copyOf(configured);
  }

  public FeeQuote calculate(FeeType type, FeeContext context) {
    Objects.requireNonNull(type, "Fee type cannot be null");
    Objects.requireNonNull(context, "Fee context cannot be null");
    FeePolicy policy = policies.get(type);
    if (policy == null) {
      throw new IllegalStateException("No fee policy configured for " + type);
    }
    return policy.calculate(context);
  }
}
