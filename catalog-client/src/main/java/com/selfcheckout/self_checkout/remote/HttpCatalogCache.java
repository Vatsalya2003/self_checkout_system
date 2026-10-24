package com.selfcheckout.self_checkout.remote;

import com.selfcheckout.self_checkout.persistence.repository.CatalogCache;
import com.selfcheckout.self_checkout.persistence.repository.CatalogEntry;
import com.selfcheckout.self_checkout.transactions.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// CATALOG-CLIENT library, remote layer: the catalog as checkout, inventory and
// analytics see it -- loaded over HTTP from catalog-service, then served from
// memory.
//
// Week 5: each microservice owns its own database, so nobody but
// catalog-service can read the items table any more. The other three call its
// public GET /items once (directly on its port, not through the gateway) and
// cache the answer. That is safe because the catalog never changes during a
// run; stock, which does change, is not in here.
//
// Loaded lazily, with a background warm-up started by each service at boot
// (warmUp()). If catalog-service is not up yet, the first request that needs a
// price waits briefly and then gets 503 instead of hanging -- and once the
// catalog has been loaded, a catalog outage no longer affects this service.
//
// Not a @Component on purpose: catalog-service scans the same package and has
// its own database-backed cache, so each consumer declares this as a @Bean.
public class HttpCatalogCache implements CatalogCache {

    private static final Logger log = LoggerFactory.getLogger(HttpCatalogCache.class);

    /** The JSON GET /items returns: {"items":[{"sku":..,"name":..,"price":..}]}. */
    record CatalogPayload(List<Item> items) {
        record Item(String sku, String name, BigDecimal price) {}
    }

    private final RestClient http;
    private final String baseUrl;

    // volatile: written once by whichever thread loads it, read by every
    // request thread afterwards -- publishing immutable collections through a
    // volatile field needs no lock on the read path.
    private volatile Map<String, CatalogEntry> bySku;
    private volatile List<CatalogEntry> ordered;

    public HttpCatalogCache(String baseUrl) {
        this.baseUrl = baseUrl;
        // A reusable executor, on purpose. Without one, Spring's
        // JdkClientHttpRequestFactory falls back to a SimpleAsyncTaskExecutor,
        // which starts a brand-new thread to write every request body --
        // measured with JFR as the busiest threads in checkout-service.
        HttpClient client = HttpClient.newBuilder()
                .executor(Executors.newCachedThreadPool(daemonThreads("HttpCatalogCache")))
                .connectTimeout(Duration.ofSeconds(2))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(10));
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public CatalogEntry find(String sku) {
        if (sku == null) {
            return null;
        }
        Map<String, CatalogEntry> cache = bySku;
        return (cache != null ? cache : load()).get(sku);
    }

    @Override
    public String nameOf(String sku) {
        CatalogEntry e = find(sku);
        return e != null ? e.name() : sku;
    }

    @Override
    public List<CatalogEntry> all() {
        if (bySku == null) {
            load();
        }
        return ordered;
    }

    /**
     * Keep trying in the background until the catalog is loaded, so the first
     * real request never pays for it. Called once by each service at startup.
     */
    public void warmUp() {
        Thread t = new Thread(() -> {
            long backoff = 200;
            while (bySku == null) {
                try {
                    load();
                } catch (ServiceUnavailableException e) {
                    try {
                        Thread.sleep(backoff);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    backoff = Math.min(backoff * 2, 2_000);
                }
            }
        }, "catalog-warm-up");
        t.setDaemon(true);
        t.start();
    }

    private synchronized Map<String, CatalogEntry> load() {
        if (bySku != null) {            // another thread got here first
            return bySku;
        }
        CatalogPayload payload;
        try {
            payload = http.get().uri("/items").retrieve().body(CatalogPayload.class);
        } catch (RestClientException e) {
            throw new ServiceUnavailableException("Catalog is unavailable (" + baseUrl + "); try again");
        }
        if (payload == null || payload.items() == null) {
            throw new ServiceUnavailableException("Catalog returned no items; try again");
        }
        List<CatalogEntry> list = payload.items().stream()
                .map(i -> new CatalogEntry(i.sku(), i.name(), i.price()))
                .toList();                      // already SKU-ordered by catalog-service
        Map<String, CatalogEntry> m = new HashMap<>();
        for (CatalogEntry e : list) {
            m.put(e.sku(), e);
        }
        ordered = list;
        bySku = Map.copyOf(m);
        log.info("catalog loaded from {}: {} items", baseUrl, list.size());
        return bySku;
    }

    /** Named daemon threads, so they never keep the JVM alive at shutdown. */
    static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
