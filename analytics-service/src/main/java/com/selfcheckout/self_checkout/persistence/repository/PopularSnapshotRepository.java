package com.selfcheckout.self_checkout.persistence.repository;

import com.selfcheckout.self_checkout.persistence.entity.PopularSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

// ANALYTICS-SERVICE, persistence layer.
// REPOSITORY layer: stored popular-items windows.
public interface PopularSnapshotRepository extends JpaRepository<PopularSnapshot, Long> {

    /** The most recently computed window. */
    // Derived query again: "findFirst" = LIMIT 1, "ByOrderByIdDesc" = newest
    // first. Snapshots are append-only, so the highest id is the latest window.
    Optional<PopularSnapshot> findFirstByOrderByIdDesc();

    /**
     * The window with the highest window_end: where analytics-service resumes
     * after a restart. It reloads the scans of that window from scan_log and
     * carries on with the very next seq, so a restart neither repeats nor
     * skips a window.
     */
    Optional<PopularSnapshot> findFirstByOrderByWindowEndDesc();
}
