package com.selfcheckout.self_checkout.analytics.pipeline;

import java.util.Collection;
import java.util.Optional;

/**
 * A pipe: the one-way connection between two filters.
 *
 * This is the seam of the whole design. Today every pipe is an in-process
 * bounded queue, which is the right answer for a single JVM. Swapping in a
 * message broker later means writing one more implementation of this
 * interface -- the filters never find out.
 *
 * Shutdown works by "poisoning" the pipe rather than by passing a special
 * sentinel message through it. A sentinel would have to be smuggled past the
 * generic type; a flag plus "the queue is empty" gives the same ordering
 * guarantee (everything already queued is processed first) with no casting.
 */
public interface Pipe<T> {

    /** Block until there is room. Used BETWEEN filters, where backpressure belongs. */
    void put(T item) throws InterruptedException;

    /**
     * Try to hand over an item, giving up after timeoutMillis.
     * Used at the request-thread boundary, where blocking must never happen:
     * a stalled analytics pipeline must not be able to stall checkout.
     *
     * @return true if accepted, false if the pipe stayed full
     */
    boolean offer(T item, long timeoutMillis) throws InterruptedException;

    /** Next item, or empty if nothing arrived within timeoutMillis. */
    Optional<T> poll(long timeoutMillis) throws InterruptedException;

    /** Move up to max already-available items into out, without blocking. */
    int drainTo(Collection<? super T> out, int max);

    /** Mark the upstream end closed. Queued items are still delivered first. */
    void poison();

    /** True once the pipe is poisoned AND everything queued has been taken. */
    boolean isFinished();

    /** How many items are waiting right now. */
    int depth();

    int capacity();

    /** Deepest this pipe has ever been -- the evidence that it never filled up. */
    int maxDepth();

    /** How many times put() had to wait because the pipe was full. */
    long fullWaits();

    String name();
}
