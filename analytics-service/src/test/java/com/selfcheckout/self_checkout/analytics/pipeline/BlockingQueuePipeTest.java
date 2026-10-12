package com.selfcheckout.self_checkout.analytics.pipeline;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockingQueuePipeTest {

    @Test
    @DisplayName("offer refuses rather than blocking when the pipe is full")
    void offerRefusesWhenFull() throws Exception {
        Pipe<String> p = new BlockingQueuePipe<>("p", 2);
        assertTrue(p.offer("a", 10));
        assertTrue(p.offer("b", 10));

        long before = System.currentTimeMillis();
        assertFalse(p.offer("c", 10), "third offer must be refused, not queued");
        long waited = System.currentTimeMillis() - before;
        assertTrue(waited < 1000, "offer must give up quickly, waited " + waited + "ms");
        assertEquals(2, p.depth());
    }

    @Test
    @DisplayName("poison is ordered after everything already queued")
    void poisonDoesNotJumpTheQueue() throws Exception {
        Pipe<String> p = new BlockingQueuePipe<>("p", 10);
        p.put("a");
        p.put("b");
        p.poison();

        assertFalse(p.isFinished(), "still has a backlog");
        assertEquals("a", p.poll(10).orElseThrow());
        assertFalse(p.isFinished());
        assertEquals("b", p.poll(10).orElseThrow());
        assertTrue(p.isFinished(), "finished only once drained");
    }

    @Test
    @DisplayName("drainTo takes what is there, up to the cap")
    void drainToRespectsCap() throws Exception {
        Pipe<String> p = new BlockingQueuePipe<>("p", 100);
        for (int i = 0; i < 50; i++) p.put("x" + i);
        List<String> out = new ArrayList<>();
        assertEquals(20, p.drainTo(out, 20));
        assertEquals(30, p.depth());
    }

    @Test
    @DisplayName("tracks high-water depth and full waits as evidence")
    void tracksMetrics() throws Exception {
        Pipe<String> p = new BlockingQueuePipe<>("p", 5);
        for (int i = 0; i < 4; i++) p.put("x" + i);
        assertEquals(4, p.maxDepth());
        assertEquals(0, p.fullWaits(), "never had to wait");
        p.poll(10);
        assertEquals(3, p.depth());
        assertEquals(4, p.maxDepth(), "high-water mark does not go back down");
    }
}
