package com.selfcheckout.self_checkout.remote;

import java.util.Map;

// CHECKOUT-SERVICE, remote layer. What one call to inventory's
// POST /internal/stock/commit told us. Exactly three possibilities, and the
// payment saga does something different for each:
//
//   Committed     inventory answered 200: the stock commit is durable.
//                 applied = units actually taken per SKU (the clamp);
//                 replayed = this id had already been committed earlier.
//   NotDelivered  we know for certain inventory did NOT commit: the
//                 connection was never made, or inventory answered with an
//                 error (it answers only after its transaction rolled back).
//   Uncertain     the request may have reached inventory but no answer came
//                 back (timeout, connection dropped mid-response). It may or
//                 may not have committed.
public sealed interface StockCommitOutcome {

    record Committed(Map<String, Integer> applied, boolean replayed) implements StockCommitOutcome {}

    record NotDelivered(String reason) implements StockCommitOutcome {}

    record Uncertain(String reason) implements StockCommitOutcome {}
}
