package com.selfcheckout.self_checkout.transactions;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The basket is the one piece of checkout state that no longer lives in the
 * database, so the guarantees it used to get from Postgres -- ordering, "a
 * row can only be completed once" -- now have to come from this class and be
 * tested here.
 */
class OpenBasketTest {

    private static final BigDecimal PRICE = new BigDecimal("1.37");

    private OpenBasket basket() {
        return new OpenBasket("tx-1", "station-1", Instant.EPOCH);
    }

    @Test
    @DisplayName("lines keep the order the SKUs were first scanned in")
    void linesKeepFirstScanOrder() {
        // Regression test. The snapshot used Map.copyOf, whose iteration
        // order is unspecified, so receipt lines came out in hash order --
        // the same basket produced its lines in a different order from every
        // previous version of the server. Scanning in descending SKU order
        // makes any re-sorting obvious.
        OpenBasket b = basket();
        List<String> scanned = List.of("SKU-000009", "SKU-000004", "SKU-000007",
                                       "SKU-000001", "SKU-000002");
        for (String sku : scanned) {
            b.addUnit(sku, PRICE);
        }

        assertEquals(scanned, List.copyOf(b.snapshot().lines().keySet()),
                "receipt line order must follow first scan, not hash order");
        assertEquals(scanned, List.copyOf(b.claimForCompletion().lines().keySet()),
                "the claimed snapshot must preserve that order too");
    }

    @Test
    @DisplayName("rescanning a SKU bumps its quantity without moving its line")
    void repeatScanDoesNotReorder() {
        OpenBasket b = basket();
        b.addUnit("SKU-000003", PRICE);
        b.addUnit("SKU-000001", PRICE);
        b.addUnit("SKU-000003", PRICE);          // second unit of the FIRST line

        OpenBasket.Snapshot s = b.snapshot();
        assertEquals(List.of("SKU-000003", "SKU-000001"), List.copyOf(s.lines().keySet()));
        assertEquals(2, s.lines().get("SKU-000003"));
        assertEquals(3, s.itemCount());
        assertEquals(new BigDecimal("4.11"), s.runningTotal());
    }

    @Test
    @DisplayName("the snapshot cannot be used to mutate the basket")
    void snapshotIsImmutable() {
        OpenBasket b = basket();
        b.addUnit("SKU-000001", PRICE);
        assertThrows(UnsupportedOperationException.class,
                () -> b.snapshot().lines().put("SKU-000002", 1));
    }

    @Test
    @DisplayName("only the first complete claims the basket; a later scan is refused")
    void claimIsAOneWayDoor() {
        OpenBasket b = basket();
        b.addUnit("SKU-000001", PRICE);

        assertNotNull(b.claimForCompletion(), "the first claim wins");
        assertNull(b.claimForCompletion(), "a second complete must be refused -> 409");
        assertNull(b.addUnit("SKU-000002", PRICE), "a scan arriving mid-complete must be refused -> 409");
    }

    @Test
    @DisplayName("an empty basket is not claimed, so it stays completable")
    void emptyBasketStaysOpen() {
        OpenBasket b = basket();

        OpenBasket.Snapshot s = b.claimForCompletion();
        assertNotNull(s);
        assertTrue(s.lines().isEmpty(), "caller turns this into 409 TRANSACTION_EMPTY");
        assertEquals(OpenBasket.State.OPEN, s.state(),
                "it must NOT be claimed -- this is how it behaved when an empty "
                + "complete was a database transaction that rolled back");

        // ...and it can still be scanned into and completed afterwards.
        assertNotNull(b.addUnit("SKU-000001", PRICE));
        assertNotNull(b.claimForCompletion());
    }

    @Test
    @DisplayName("releasing a failed claim makes the basket completable again")
    void releaseClaimReopens() {
        OpenBasket b = basket();
        b.addUnit("SKU-000001", PRICE);
        b.claimForCompletion();

        b.releaseClaim();                        // the database write failed

        assertNotNull(b.claimForCompletion(), "a retry must be able to claim it again");
    }
}
