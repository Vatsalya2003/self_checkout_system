package com.selfcheckout.self_checkout.analytics;

import com.selfcheckout.self_checkout.persistence.repository.ScanLogRow;
import com.selfcheckout.self_checkout.persistence.repository.ScanStore;
import com.selfcheckout.self_checkout.transactions.exception.InvalidRequestException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// ANALYTICS-SERVICE, business layer: storing the scans checkout publishes.
//
// Week 5: checkout numbered the scans and POSTed them here. Week 6: they
// arrive as ScansRecorded events from RabbitMQ (ScanBatchListener), with NO
// numbers, and this class numbers them as it stores them. In ONE transaction:
//
//   1. take the numbering lock (only one numbering transaction at a time)
//   2. next seq = MAX(seq) + 1
//   3. claim the batch id in scan_batches -- if it is already there, this is
//      a redelivered batch: store nothing, number nothing
//   4. insert the scans as seq next .. next + n - 1
//   5. commit -- only then is the message acked
//
// The guarantees the windows depend on:
//   gapless     only this transaction assigns seqs, from MAX + 1, and a
//               rolled-back batch takes its numbers back with it
//   no doubles  the batch id is the redelivery check (step 3)
//   in order    one consumer, one batch at a time, in the order checkout's
//               single writer thread published them
// From scan_log on, nothing changed: ScanLogPoller reads it exactly as in
// Week 4. Week 5's GET /internal/scans/max-seq is gone: checkout no longer
// needs to know where the numbering is.
@Service
public class ScanIngestService {

    /** One published scan, as the listener hands it over. */
    public record IncomingScan(String sku, Instant scannedAt) {}

    /** What one batch did: stored as first..last, or skipped as a duplicate. */
    public record IngestResult(boolean duplicate, int stored, long firstSeq, long lastSeq) {}

    private final ScanStore store;

    public ScanIngestService(ScanStore store) {
        this.store = store;
    }

    @Transactional
    public IngestResult accept(String batchId, List<IncomingScan> scans) {
        if (batchId == null || batchId.isBlank()) {
            throw new InvalidRequestException("a batch needs a batchId");
        }
        if (scans == null || scans.isEmpty()) {
            throw new InvalidRequestException("a batch needs at least one scan");
        }
        for (IncomingScan s : scans) {
            if (s == null || s.sku() == null || s.scannedAt() == null) {
                throw new InvalidRequestException("every scan needs a sku and a time");
            }
        }
        store.lockNumbering();
        long first = store.maxSeq() + 1;
        long last = first + scans.size() - 1;
        if (!store.claimBatch(batchId, first, last)) {
            return new IngestResult(true, 0, 0, 0);
        }
        List<ScanLogRow> rows = new ArrayList<>(scans.size());
        long seq = first;
        for (IncomingScan s : scans) {                 // keep checkout's order
            rows.add(new ScanLogRow(seq++, s.sku(), s.scannedAt()));
        }
        store.insert(rows);
        return new IngestResult(false, rows.size(), first, last);
    }
}
