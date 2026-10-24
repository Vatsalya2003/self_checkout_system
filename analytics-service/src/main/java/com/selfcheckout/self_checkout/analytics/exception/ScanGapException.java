package com.selfcheckout.self_checkout.analytics.exception;

import com.selfcheckout.self_checkout.transactions.exception.DomainException;

// ANALYTICS-SERVICE. A pushed batch would leave a hole in scan_log (its first
// seq is beyond max + 1). The window filter's "seq % 500 == 0" boundaries
// need a gapless sequence, so the batch is refused with 409 and checkout
// re-reads max-seq before sending again.
public class ScanGapException extends DomainException {
    private final long maxSeq;

    public ScanGapException(String message, long maxSeq) {
        super(message);
        this.maxSeq = maxSeq;
    }

    public long maxSeq() {
        return maxSeq;
    }
}
