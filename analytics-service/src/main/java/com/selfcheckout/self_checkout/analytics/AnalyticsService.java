package com.selfcheckout.self_checkout.analytics;

import com.selfcheckout.self_checkout.analytics.result.PopularEntry;
import com.selfcheckout.self_checkout.analytics.result.PopularWindowView;
import com.selfcheckout.self_checkout.config.SelfCheckoutProperties;
import com.selfcheckout.self_checkout.persistence.repository.PopularSnapshotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

/**
 * ANALYTICS-SERVICE, business layer -- the read side.
 *
 * Serves GET /analytics/popular-items from the newest stored window.
 *
 * Week 4 change: this class no longer receives scans. In the single application
 * the checkout code called ScanRecorder.recordScan() on this class and the scan
 * went straight into an in-process queue. Checkout and analytics are now
 * separate processes. Week 4 handed scans over through a shared table; since
 * Week 5 (a database per service) checkout-service pushes numbered batches to
 * POST /internal/scans, ScanIngestService stores them in this service's own
 * scan_log, and ScanLogPoller reads them back in order (see AnalyticsPipeline).
 */
@Service
public class AnalyticsService {

    /** The assignment requires the top 10 to be written to the database. */
    private static final int STORED_TOP_N = 10;

    private final PopularSnapshotRepository popularSnapshotRepository;
    private final SelfCheckoutProperties properties;
    private final ObjectMapper objectMapper;

    public AnalyticsService(PopularSnapshotRepository popularSnapshotRepository,
                            SelfCheckoutProperties properties,
                            ObjectMapper objectMapper) {
        this.popularSnapshotRepository = popularSnapshotRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
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
