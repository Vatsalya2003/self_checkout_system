package com.selfcheckout.self_checkout.analytics;

import com.selfcheckout.self_checkout.analytics.exception.ScanGapException;
import com.selfcheckout.self_checkout.persistence.repository.ScanLogRow;
import com.selfcheckout.self_checkout.persistence.repository.ScanStore;
import com.selfcheckout.self_checkout.transactions.exception.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

// ANALYTICS-SERVICE, business layer: accepting scans pushed by checkout.
//
// Week 5. The hand-off used to be a shared table. Now checkout's single writer
// thread POSTs numbered batches here, waits for the answer, and only then
// sends the next batch -- so batches arrive in order. This class makes the
// three guarantees the windows depend on:
//   gapless    a batch must continue from what is stored (else 409 SCAN_GAP)
//   no doubles a retried batch is absorbed by the seq primary key
//   durable    the answer is sent only after the INSERT has committed
// From the table on, nothing changed: ScanLogPoller reads it exactly as in
// Week 4.
@Service
public class ScanIngestService {

    /** One pushed scan, as the API layer hands it over. */
    public record IncomingScan(long seq, String sku, Instant scannedAt) {}

    /** What one push did. */
    public record IngestResult(int inserted, int duplicates, long maxSeq) {}

    private final ScanStore store;

    public ScanIngestService(ScanStore store) {
        this.store = store;
    }

    @Transactional
    public IngestResult accept(List<IncomingScan> scans) {
        if (scans == null || scans.isEmpty()) {
            throw new InvalidRequestException("a batch needs at least one scan");
        }
        List<ScanLogRow> sorted = scans.stream()
                .map(s -> s == null ? new ScanLogRow(0, null, null) : new ScanLogRow(s.seq(), s.sku(), s.scannedAt()))
                .sorted(Comparator.comparingLong(ScanLogRow::seq))
                .toList();
        for (int i = 0; i < sorted.size(); i++) {
            ScanLogRow r = sorted.get(i);
            if (r.sku() == null || r.scannedAt() == null || r.seq() <= 0) {
                throw new InvalidRequestException("every scan needs a positive seq, a sku and a time");
            }
            if (i > 0 && r.seq() != sorted.get(i - 1).seq() + 1) {
                throw new InvalidRequestException("a batch must be a contiguous run of seq numbers");
            }
        }
        long max = store.maxSeq();
        if (sorted.get(0).seq() > max + 1) {
            throw new ScanGapException("batch starts at seq " + sorted.get(0).seq()
                    + " but only 1.." + max + " are stored", max);
        }
        int inserted = store.insertNew(sorted);
        long newMax = Math.max(max, sorted.get(sorted.size() - 1).seq());
        return new IngestResult(inserted, sorted.size() - inserted, newMax);
    }

    public long maxSeq() {
        return store.maxSeq();
    }
}
