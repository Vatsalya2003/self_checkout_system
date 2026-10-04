package com.selfcheckout.self_checkout.analytics;

import com.selfcheckout.self_checkout.analytics.pipeline.AnalyticsPipeline;
import com.selfcheckout.self_checkout.analytics.result.PopularEntry;
import com.selfcheckout.self_checkout.analytics.result.PopularWindowView;
import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import com.selfcheckout.self_checkout.persistence.repository.PopularSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * ANALYTICS layer -- the thin edge of it.
 *
 * Week 3 moved all the actual work into a pipeline of filters (see
 * analytics.pipeline and PIPELINE.md). What is left here is the two ends:
 *
 *   write side -- ScanRecorder.recordScan(): hand one event to the pipeline
 *   read side  -- latest(): serve GET /analytics/popular-items
 *
 * What used to be here and is now gone:
 *   - the per-scan scan_log INSERT (the scan-log writer filter batches them)
 *   - the SQL top-10 recompute (the window + rank filters do it in memory)
 *   - the AtomicLong boundary claim (the window filter owns boundaries, and
 *     the sequence it counts has no gaps, so the trick is unnecessary -- see
 *     WindowFilter.accept for the full explanation)
 */
@Service
public class AnalyticsService implements ScanRecorder {

    /** The assignment requires the top 10 to be written to the database. */
    private static final int STORED_TOP_N = 10;

    private final AnalyticsPipeline pipeline;
    private final PopularSnapshotRepository popularSnapshotRepository;
    private final SelfCheckoutProperties properties;
    private final ObjectMapper objectMapper;

    public AnalyticsService(AnalyticsPipeline pipeline,
                            PopularSnapshotRepository popularSnapshotRepository,
                            SelfCheckoutProperties properties,
                            ObjectMapper objectMapper) {
        this.pipeline = pipeline;
        this.popularSnapshotRepository = popularSnapshotRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * The ScanRecorder door: "one unit of this SKU was just scanned".
     *
     * Does no I/O at all now. It only registers a callback; the handover to
     * the pipeline happens after the scan's COMMIT, on this same request
     * thread, and is a bounded in-memory offer.
     *
     * Registering on afterCommit keeps the Week 2 guarantee intact: a scan
     * that rolls back never reaches analytics, because afterCommit simply
     * never fires.
     */
    @Override
    public void recordScan(String sku) {
        // Captured here, inside the transaction, so the timestamp reflects
        // when the item was actually scanned rather than when the pipeline
        // got round to it.
        Instant scannedAt = Instant.now();

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    pipeline.submit(sku, scannedAt);
                }
            });
        } else {
            // No transaction in progress (e.g. called directly from a test).
            pipeline.submit(sku, scannedAt);
        }
    }

    /** Latest persisted window. Well-formed and empty if none has been computed yet. */
    @Transactional(readOnly = true)
    public PopularWindowView latest(Integer limit) {
        int windowSize = properties.popularWindowSize();
        int slide = properties.popularSlideInterval();
        int effectiveLimit = (limit == null || limit < 0) ? STORED_TOP_N : limit;

        return popularSnapshotRepository.findFirstByOrderByIdDesc()
                .map(s -> {
                    // Read the stored JSON back into records. TypeReference is
                    // needed because generics are erased -- List<PopularEntry>
                    // alone isn't enough for Jackson to know the element type.
                    List<PopularEntry> items = objectMapper.readValue(
                            s.getItemsJson(), new TypeReference<List<PopularEntry>>() {});
                    // We only ever stored 10, so asking for more just gets 10.
                    if (items.size() > effectiveLimit) {
                        items = items.subList(0, effectiveLimit);
                    }
                    return new PopularWindowView(windowSize, slide, s.getWindowStart(),
                            s.getWindowEnd(), s.getComputedAt(), items);
                })
                // Nothing computed yet: still return a valid, well-formed body
                // with an empty list, never a 404 or a null.
                .orElseGet(() -> new PopularWindowView(windowSize, slide, 0, 0, Instant.now(), List.of()));
    }
}
