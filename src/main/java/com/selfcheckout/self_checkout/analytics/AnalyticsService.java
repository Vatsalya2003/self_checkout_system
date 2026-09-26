package com.selfcheckout.self_checkout.analytics;

import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import com.selfcheckout.self_checkout.analytics.result.PopularEntry;
import com.selfcheckout.self_checkout.analytics.result.PopularWindowView;
import com.selfcheckout.self_checkout.persistence.entity.PopularSnapshot;
import com.selfcheckout.self_checkout.persistence.entity.ScanLog;
import com.selfcheckout.self_checkout.persistence.repository.PopularSnapshotRepository;
import com.selfcheckout.self_checkout.persistence.repository.ScanCount;
import com.selfcheckout.self_checkout.persistence.repository.ScanLogRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Popular items over a hopping window: the most recent windowSize scans,
 * recomputed every slideInterval scans, with each result persisted.
 */
// ANALYTICS layer -- the whole of it.
//
// "Hopping window" = a window of fixed WIDTH (1000 scans) that jumps forward
// in fixed STEPS (500 scans). So windows overlap: (0,1000], (500,1500],
// (1000,2000]... Each scan is counted in two consecutive windows.
//
// Heads up for Spring Boot 4: the ObjectMapper below is Jackson 3, which moved
// to the "tools.jackson" package. The old com.fasterxml import won't resolve.
@Service
public class AnalyticsService implements ScanRecorder {

    private static final Logger log = LoggerFactory.getLogger(AnalyticsService.class);

    /** The assignment requires the top 10 to be written to the database. */
    private static final int STORED_TOP_N = 10;

    private final ScanLogRepository scanLogRepository;
    private final PopularSnapshotRepository popularSnapshotRepository;
    private final SelfCheckoutProperties properties;
    private final ObjectMapper objectMapper;
    private final ExecutorService recomputeExecutor;

    /**
     * Highest window boundary already claimed by some thread.
     *
     * Note what this is NOT: a check for seq % slideInterval == 0. scan_log.seq
     * is a BIGSERIAL, and sequences hand out numbers that a rolled-back insert
     * simply consumes -- so an exact multiple of 500 can never appear and a
     * window would be silently skipped forever. Instead each scan works out
     * which boundary its own seq has passed and tries to claim it. Whoever
     * wins the compareAndSet does the recompute; everyone else moves on.
     */
    // AtomicLong (not a plain long) because many Tomcat threads call
    // onScanRecorded at the same time and we need exactly one winner.
    private final AtomicLong lastBoundary = new AtomicLong(0);

    public AnalyticsService(ScanLogRepository scanLogRepository,
                            PopularSnapshotRepository popularSnapshotRepository,
                            SelfCheckoutProperties properties,
                            ObjectMapper objectMapper,
                            ExecutorService recomputeExecutor) {
        this.scanLogRepository = scanLogRepository;
        this.popularSnapshotRepository = popularSnapshotRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.recomputeExecutor = recomputeExecutor;
    }

    /**
     * The ScanRecorder door: "one unit of this SKU was just scanned".
     *
     * Writes the scan_log row and then checks whether that scan crossed a
     * window boundary. Deliberately NOT annotated with @Transactional, so it
     * joins the transaction the scan is already running in -- if the scan
     * rolls back, this row rolls back with it.
     *
     * This used to live in TransactionService. It moved here so the
     * transactions layer no longer has to know that scan_log exists, or that
     * scans are numbered at all.
     */
    @Override
    public void recordScan(String sku) {
        ScanLog logged = scanLogRepository.save(new ScanLog(sku));
        onScanRecorded(logged.getSeq());
    }

    /**
     * Called with the seq just assigned.
     * Costs one integer division and one atomic read on the hot path; the
     * aggregate query itself never runs on a request thread.
     */
    private void onScanRecorded(long seq) {
        int slide = properties.popularSlideInterval();
        // Which boundary has this scan passed? seq 1499 with slide 500 -> 1000.
        // Integer division, so this works even if seq numbers were skipped.
        long boundary = (seq / slide) * slide;
        if (boundary <= lastBoundary.get()) {
            return;                                   // no boundary crossed
        }
        long previous = lastBoundary.get();
        // compareAndSet = "only set it if nobody changed it since I read it".
        // Exactly one thread can win for a given boundary; the losers bail out
        // here, so a window is never computed twice.
        if (boundary <= previous || !lastBoundary.compareAndSet(previous, boundary)) {
            return;                                   // another thread claimed it
        }

        // Hand off only once the scan has actually committed, so the window
        // cannot include a scan that later rolls back, and so the request
        // thread never pays for the aggregate.
        // afterCommit fires after COMMIT succeeds; if the scan rolls back it
        // never fires at all, which is exactly what we want.
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    submit(boundary);
                }
            });
        } else {
            // No transaction in progress (e.g. called from a test) -- just go.
            submit(boundary);
        }
    }

    private void submit(long windowEnd) {
        try {
            recomputeExecutor.submit(() -> {
                try {
                    recomputeWindow(windowEnd);
                } catch (RuntimeException e) {
                    // Analytics must never take the checkout path down.
                    // Worst case we lose one window; the next one is 500 scans away.
                    log.warn("Popular-items recompute failed for window ending {}", windowEnd, e);
                }
            });
        } catch (RuntimeException e) {
            log.warn("Could not schedule popular-items recompute for window ending {}", windowEnd, e);
        }
    }

    /**
     * Aggregate the window and persist it. REQUIRES_NEW because this runs on
     * the recompute thread, entirely outside any request's transaction, and
     * must never join or block one.
     */
    // Must be public and called from outside the class, otherwise Spring's
    // proxy is bypassed and @Transactional would silently do nothing.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recomputeWindow(long windowEnd) {
        // Early in a run there aren't 1000 scans yet, so clamp the start at 0.
        long windowStart = Math.max(0, windowEnd - properties.popularWindowSize());
        List<ScanCount> counts = scanLogRepository.topInWindow(windowStart, windowEnd, Limit.of(STORED_TOP_N));

        // The query already sorted by count desc, so position = rank.
        List<PopularEntry> items = new ArrayList<>(counts.size());
        for (int i = 0; i < counts.size(); i++) {
            ScanCount c = counts.get(i);
            items.add(new PopularEntry(c.sku(), c.name(), c.scanCount(), i + 1));   // rank is 1-based
        }
        popularSnapshotRepository.save(new PopularSnapshot(
                windowStart, windowEnd, Instant.now(), objectMapper.writeValueAsString(items)));
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
                    // needed because generics are erased -- List<PopularItem>
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
