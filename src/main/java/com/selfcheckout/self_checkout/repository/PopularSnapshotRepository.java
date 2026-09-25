package com.selfcheckout.self_checkout.repository;

import com.selfcheckout.self_checkout.entity.PopularSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// REPOSITORY layer: stored popular-items windows.
public interface PopularSnapshotRepository extends JpaRepository<PopularSnapshot, Long> {

    /** The most recently computed window. */
    // Derived query again: "findFirst" = LIMIT 1, "ByOrderByIdDesc" = newest
    // first. Snapshots are append-only, so the highest id is the latest window.
    Optional<PopularSnapshot> findFirstByOrderByIdDesc();
}
