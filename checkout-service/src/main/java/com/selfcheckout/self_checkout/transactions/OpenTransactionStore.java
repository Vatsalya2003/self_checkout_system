package com.selfcheckout.self_checkout.transactions;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * TRANSACTIONS layer. Every basket that has been started but not yet paid for.
 *
 * Why this exists: a basket in progress is not durable business data. Nobody
 * has paid, no stock has moved, and if the process dies the customer simply
 * starts again. Writing it to Postgres on every single scan cost a transaction,
 * several round trips and a full-row rewrite per beep, for data that is thrown
 * away the moment it matters.
 *
 * What it deliberately does NOT hold: anything about a PAID transaction, or
 * stock. Those stay fully transactional in the database.
 *
 * Trade-off, stated plainly: a crash loses open, unpaid baskets. It can never
 * lose a paid transaction and can never corrupt stock. That is the trade the
 * Week 1 brief named, and it is the one being taken here.
 */
@Component
public class OpenTransactionStore {

    // ConcurrentHashMap, not a synchronized map: different stations touch
    // different baskets, so the map itself must not be a bottleneck. Each
    // basket then guards its own contents with its own lock.
    private final ConcurrentHashMap<String, OpenBasket> open = new ConcurrentHashMap<>();

    void put(OpenBasket basket) {
        open.put(basket.id(), basket);
    }

    /** The basket, or null if there is no OPEN basket with that id. */
    OpenBasket find(String id) {
        return id == null ? null : open.get(id);
    }

    /** Called once a basket has been durably written as COMPLETED. */
    void remove(String id) {
        open.remove(id);
    }

    /** How many baskets are in flight -- useful when reading the logs. */
    public int size() {
        return open.size();
    }
}
