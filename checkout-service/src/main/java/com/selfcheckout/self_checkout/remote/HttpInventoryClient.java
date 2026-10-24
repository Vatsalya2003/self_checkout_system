package com.selfcheckout.self_checkout.remote;

import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// CHECKOUT-SERVICE, remote layer: POST /internal/stock/commit on
// inventory-service (directly on :8083, never through the gateway).
//
// The interesting part is sorting a failure into the right bucket, because the
// saga's safety depends on it (see StockCommitOutcome):
//   - the TCP connection was refused or never opened  -> NotDelivered
//   - inventory answered with an error status         -> NotDelivered
//     (it only answers after its own transaction rolled back)
//   - anything that went wrong AFTER the request may have been sent
//     (read timeout, connection reset)                -> Uncertain
public class HttpInventoryClient implements InventoryClient {

    record Request(String transactionId, List<Line> lines) {
        record Line(String sku, int quantity) {}
    }
    record Response(String transactionId, boolean replayed, List<Line> lines) {
        record Line(String sku, int quantity, int applied) {}
    }

    private final RestClient http;

    public HttpInventoryClient(String inventoryUrl, Duration readTimeout) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)      // no h2c upgrade round trip
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        // Long enough for the slowest commit measured under stress (a queue
        // for the hottest SKU's row lock, ~1-2 s at worst), short enough to
        // answer the customer well inside the load client's 10 s timeout.
        factory.setReadTimeout(readTimeout);
        this.http = RestClient.builder().baseUrl(inventoryUrl).requestFactory(factory).build();
    }

    @Override
    public StockCommitOutcome commit(String transactionId, Map<String, Integer> quantityBySku) {
        Request body = new Request(transactionId, quantityBySku.entrySet().stream()
                .map(e -> new Request.Line(e.getKey(), e.getValue())).toList());
        try {
            Response r = http.post().uri("/internal/stock/commit")
                    .contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(Response.class);
            if (r == null || r.lines() == null) {
                return new StockCommitOutcome.Uncertain("empty answer from inventory");
            }
            Map<String, Integer> applied = new HashMap<>();
            r.lines().forEach(l -> applied.put(l.sku(), l.applied()));
            return new StockCommitOutcome.Committed(applied, r.replayed());
        } catch (RestClientResponseException e) {
            // Any status inventory chose to send: its transaction rolled back.
            return new StockCommitOutcome.NotDelivered("inventory answered " + e.getStatusCode().value());
        } catch (ResourceAccessException e) {
            return neverConnected(e)
                    ? new StockCommitOutcome.NotDelivered("inventory unreachable: " + rootMessage(e))
                    : new StockCommitOutcome.Uncertain("no answer from inventory: " + rootMessage(e));
        }
    }

    /** True only if the failure happened before the request could have been sent. */
    private static boolean neverConnected(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }
}
