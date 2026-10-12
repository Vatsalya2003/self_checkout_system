package com.selfcheckout.self_checkout.analytics.pipeline;

import java.util.Collection;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The default pipe: a bounded ArrayBlockingQueue.
 *
 * Bounded, not unbounded, on purpose. An unbounded queue turns "analytics is
 * falling behind" into "the heap fills up and the JVM dies" -- the failure
 * arrives much later and much worse. A bound makes the problem visible
 * immediately, as either backpressure or a counted refusal.
 */
public class BlockingQueuePipe<T> implements Pipe<T> {

    private final String name;
    private final int capacity;
    private final BlockingQueue<T> queue;

    // Poison is a flag, not a queued message, so it can never jump ahead of
    // items that are already waiting.
    private volatile boolean poisoned = false;

    private final AtomicLong fullWaits = new AtomicLong();
    private volatile int maxDepth = 0;

    public BlockingQueuePipe(String name, int capacity) {
        this.name = name;
        this.capacity = capacity;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    @Override
    public void put(T item) throws InterruptedException {
        if (!queue.offer(item)) {          // fast path: room available
            fullWaits.incrementAndGet();   // it was full -- we are about to block
            queue.put(item);
        }
        recordDepth();
    }

    @Override
    public boolean offer(T item, long timeoutMillis) throws InterruptedException {
        boolean accepted = queue.offer(item, timeoutMillis, TimeUnit.MILLISECONDS);
        if (accepted) {
            recordDepth();
        }
        return accepted;
    }

    @Override
    public Optional<T> poll(long timeoutMillis) throws InterruptedException {
        return Optional.ofNullable(queue.poll(timeoutMillis, TimeUnit.MILLISECONDS));
    }

    @Override
    public int drainTo(Collection<? super T> out, int max) {
        return queue.drainTo(out, max);
    }

    @Override
    public void poison() {
        poisoned = true;
    }

    @Override
    public boolean isFinished() {
        // Order matters: only finished once the backlog is gone.
        return poisoned && queue.isEmpty();
    }

    private void recordDepth() {
        int d = queue.size();
        if (d > maxDepth) {
            maxDepth = d;   // racy by a hair between threads, which is fine for a
        }                   // high-water mark we only ever report
    }

    @Override public int depth()      { return queue.size(); }
    @Override public int capacity()   { return capacity; }
    @Override public int maxDepth()   { return maxDepth; }
    @Override public long fullWaits() { return fullWaits.get(); }
    @Override public String name()    { return name; }
}
