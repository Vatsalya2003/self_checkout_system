package com.selfcheckout.self_checkout.remote;

import com.selfcheckout.self_checkout.messaging.Events.CatalogPublished;
import com.selfcheckout.self_checkout.messaging.Events.CatalogRequested;
import com.selfcheckout.self_checkout.messaging.Topology;
import com.selfcheckout.self_checkout.persistence.repository.CatalogCache;
import com.selfcheckout.self_checkout.persistence.repository.CatalogEntry;
import com.selfcheckout.self_checkout.transactions.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// CATALOG-CLIENT library, remote layer: the catalog as checkout, inventory and
// analytics see it -- received as an event from catalog-service, then served
// from memory.
//
// Week 6: Week 5 called catalog-service's GET /items over HTTP. Now:
//   - each service listens on its own temporary queue bound to the
//     catalog.snapshots fanout exchange (declared by the @RabbitListener below:
//     a nameless queue that disappears when the service stops), and
//   - asks for a copy by sending CatalogRequested to catalog.requests.
// catalog-service answers by broadcasting CatalogPublished; the first copy to
// arrive is kept. The catalog never changes during a run, so later copies
// are ignored.
//
// Same behaviour as Week 5 otherwise: a background warm-up keeps asking until
// the catalog arrives, so the first real request never waits for it; if
// catalog-service (or the broker) is not up yet, a request that needs a price
// waits briefly and then gets 503 instead of hanging; once loaded, a catalog
// outage no longer affects this service.
//
// Not a @Component on purpose: catalog-service scans the same package and has
// its own database-backed cache, so each consumer declares this as a @Bean.
public class BrokerCatalogCache implements CatalogCache {

    private static final Logger log = LoggerFactory.getLogger(BrokerCatalogCache.class);
    private static final long WAIT_MILLIS = 2_000;

    private final RabbitTemplate rabbit;
    private final String requestedBy;
    private final CompletableFuture<Void> loaded = new CompletableFuture<>();

    // volatile: written once by the listener thread, read by every request
    // thread afterwards -- immutable collections published through a volatile
    // field need no lock on the read path.
    private volatile Map<String, CatalogEntry> bySku;
    private volatile List<CatalogEntry> ordered;

    public BrokerCatalogCache(RabbitTemplate rabbit, String requestedBy) {
        this.rabbit = rabbit;
        this.requestedBy = requestedBy;
    }

    @Override
    public CatalogEntry find(String sku) {
        if (sku == null) {
            return null;
        }
        Map<String, CatalogEntry> cache = bySku;
        return (cache != null ? cache : awaitLoaded()).get(sku);
    }

    @Override
    public String nameOf(String sku) {
        CatalogEntry e = find(sku);
        return e != null ? e.name() : sku;
    }

    @Override
    public List<CatalogEntry> all() {
        if (bySku == null) {
            awaitLoaded();
        }
        return ordered;
    }

    /** The broadcast from catalog-service. Only the first copy is used. */
    @RabbitListener(bindings = @QueueBinding(
            value = @Queue,                                   // nameless, exclusive, auto-delete
            exchange = @Exchange(value = Topology.CATALOG_SNAPSHOTS, type = ExchangeTypes.FANOUT)))
    public void onSnapshot(CatalogPublished snapshot) {
        if (bySku != null || snapshot == null || snapshot.items() == null || snapshot.items().isEmpty()) {
            return;
        }
        synchronized (this) {
            if (bySku != null) {
                return;
            }
            List<CatalogEntry> list = snapshot.items().stream()
                    .map(i -> new CatalogEntry(i.sku(), i.name(), i.price()))
                    .toList();                      // already SKU-ordered by catalog-service
            Map<String, CatalogEntry> m = new HashMap<>();
            for (CatalogEntry e : list) {
                m.put(e.sku(), e);
            }
            ordered = list;
            bySku = Map.copyOf(m);
        }
        loaded.complete(null);
        log.info("catalog received on {}: {} items", Topology.CATALOG_SNAPSHOTS, ordered.size());
    }

    /**
     * Keep asking in the background until the catalog arrives, so the first
     * real request never pays for it. Called once by each service at startup.
     */
    public void warmUp() {
        Thread t = new Thread(() -> {
            long backoff = 200;
            while (bySku == null) {
                request();
                try {
                    loaded.get(backoff, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    backoff = Math.min(backoff * 2, 2_000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    return;
                }
            }
        }, "catalog-warm-up");
        t.setDaemon(true);
        t.start();
    }

    private Map<String, CatalogEntry> awaitLoaded() {
        request();
        try {
            loaded.get(WAIT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // fall through to the check below
        }
        Map<String, CatalogEntry> cache = bySku;
        if (cache == null) {
            throw new ServiceUnavailableException("Catalog is unavailable; try again");
        }
        return cache;
    }

    private void request() {
        try {
            rabbit.convertAndSend("", Topology.CATALOG_REQUESTS, new CatalogRequested(requestedBy));
        } catch (RuntimeException e) {
            // Broker down: the warm-up (or the next request) simply asks again.
            log.debug("could not ask for the catalog: {}", e.toString());
        }
    }
}
