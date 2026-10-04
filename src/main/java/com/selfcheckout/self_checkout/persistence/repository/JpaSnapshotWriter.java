package com.selfcheckout.self_checkout.persistence.repository;

import com.selfcheckout.self_checkout.persistence.entity.PopularSnapshot;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

// PERSISTENCE layer (database access).
@Repository
public class JpaSnapshotWriter implements SnapshotWriter {

    private final PopularSnapshotRepository repository;

    public JpaSnapshotWriter(PopularSnapshotRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void insert(long windowStart, long windowEnd, Instant computedAt, String itemsJson) {
        repository.save(new PopularSnapshot(windowStart, windowEnd, computedAt, itemsJson));
    }
}
