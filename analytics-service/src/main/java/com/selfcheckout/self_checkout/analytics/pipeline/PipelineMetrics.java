package com.selfcheckout.self_checkout.analytics.pipeline;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters proving the pipeline neither lost anything nor silently stalled.
 *
 * These are not decoration: "events in == events out" per stage, a max pipe
 * depth far below capacity, and a drop count of zero are the evidence that
 * goes in ARCHITECTURE.md. Without them "it worked" would just be a claim.
 */
public class PipelineMetrics {

    private final Map<String, AtomicLong> in = new LinkedHashMap<>();
    private final Map<String, AtomicLong> out = new LinkedHashMap<>();
    private final Map<String, AtomicLong> errors = new LinkedHashMap<>();

    // Week 4: the two drop counters that used to live here (scans refused at
    // the request boundary, raw scans the audit branch could not take) moved
    // to checkout-service's ScanLogPublisher, because that is now where a scan
    // is handed over. Inside analytics nothing is ever dropped: every pipe
    // here uses a blocking put.

    public void registerStage(String stage) {
        in.computeIfAbsent(stage, k -> new AtomicLong());
        out.computeIfAbsent(stage, k -> new AtomicLong());
        errors.computeIfAbsent(stage, k -> new AtomicLong());
    }

    public void recordIn(String stage)    { in.get(stage).incrementAndGet(); }
    public void recordIn(String stage, long n)  { in.get(stage).addAndGet(n); }
    public void recordOut(String stage)   { out.get(stage).incrementAndGet(); }
    public void recordOut(String stage, long n) { out.get(stage).addAndGet(n); }
    public void recordError(String stage) { errors.get(stage).incrementAndGet(); }

    public long in(String stage)     { return in.get(stage).get(); }
    public long out(String stage)    { return out.get(stage).get(); }
    public long errors(String stage) { return errors.get(stage).get(); }

    public Iterable<String> stages() { return in.keySet(); }

    /** One line, cheap to read in a log tail during a load run. */
    public String summary(Iterable<Pipe<?>> pipes) {
        StringBuilder sb = new StringBuilder("pipeline |");
        for (String s : in.keySet()) {
            sb.append(' ').append(s).append(" in=").append(in.get(s).get())
              .append(" out=").append(out.get(s).get());
            long e = errors.get(s).get();
            if (e > 0) {
                sb.append(" ERR=").append(e);
            }
            sb.append(" |");
        }
        for (Pipe<?> p : pipes) {
            sb.append(' ').append(p.name()).append(" depth=").append(p.depth())
              .append("/max=").append(p.maxDepth()).append("/cap=").append(p.capacity());
            if (p.fullWaits() > 0) {
                sb.append(" fullWaits=").append(p.fullWaits());
            }
            sb.append(" |");
        }
        return sb.toString();
    }
}
