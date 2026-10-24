package com.selfcheckout.self_checkout.remote;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

// CHECKOUT-SERVICE, remote layer: pushes numbered scans to analytics-service
// (POST /internal/scans, directly on :8084 -- never through the gateway).
//
// Week 5's replacement for Week 4's INSERT into the shared scan_log. Called
// only by the publisher's single writer thread, which sends batch N+1 only
// after batch N has been acknowledged -- so batches reach analytics in order.
public class HttpScanSink implements ScanSink {

    record Batch(List<Scan> scans) {
        record Scan(long seq, String sku, Instant scannedAt) {}
    }
    record Result(int inserted, int duplicates, long maxSeq) {}

    private final RestClient http;

    public HttpScanSink(String analyticsUrl) {
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)      // no h2c upgrade round trip
                .connectTimeout(Duration.ofSeconds(1))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(client);
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = RestClient.builder().baseUrl(analyticsUrl).requestFactory(factory).build();
    }

    @Override
    public void push(List<ScanLogRow> rows) {
        Batch body = new Batch(rows.stream().map(r -> new Batch.Scan(r.seq(), r.sku(), r.scannedAt())).toList());
        try {
            http.post().uri("/internal/scans").contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatusCode.valueOf(409))) {
                String max = e.getResponseHeaders() == null ? null : e.getResponseHeaders().getFirst("X-Max-Seq");
                if (max != null) {
                    throw new ScanGapRejectedException(Long.parseLong(max));
                }
            }
            throw e;
        }
    }

    @Override
    public long maxSeq() {
        Result r = http.get().uri("/internal/scans/max-seq").retrieve().body(Result.class);
        if (r == null) {
            throw new IllegalStateException("analytics returned no max-seq");
        }
        return r.maxSeq();
    }
}
