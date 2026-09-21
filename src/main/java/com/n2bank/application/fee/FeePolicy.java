package com.n2bank.application.fee;

import com.n2bank.domain.model.FeeQuote;
import com.n2bank.domain.model.FeeType;

/** Strategy used to calculate one kind of fee. */
public interface FeePolicy {
  FeeType type();

  FeeQuote calculate(FeeContext context);
}
