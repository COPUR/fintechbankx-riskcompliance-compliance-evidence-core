package com.bank.compliance.infrastructure.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface SpringDataOutboxRepository extends JpaRepository<OutboxEventJpaEntity, UUID> {

    /**
     * Takes the cluster-wide relay lock for the current transaction. Only one
     * replica relays at a time, which keeps each aggregate's events in order.
     */
    @Query(value = "select pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean tryRelayLock(@Param("key") long key);

    @Query(value = """
        select * from outbox_event
        where published_at is null and parked_at is null
        order by created_seq
        limit :batchSize
        """, nativeQuery = true)
    List<OutboxEventJpaEntity> findUnpublishedBatch(@Param("batchSize") int batchSize);

    @Modifying
    @Query("delete from OutboxEventJpaEntity e where e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);

    /**
     * Marks rows an operator parked by hand (park_counted still false) as
     * counted and returns how many; the relay counts them as OperatorPark.
     */
    @Modifying
    @Query(value = """
        update outbox_event set park_counted = true
        where parked_at is not null and not park_counted
        """, nativeQuery = true)
    int markUncountedParksCounted();

    /** Events waiting to be relayed (parked rows are not waiting: the relay skips them). */
    long countByPublishedAtIsNullAndParkedAtIsNull();

    /** When the oldest event still waiting to be relayed was written; empty when none is waiting. */
    @Query(value = """
        select created_at from outbox_event
        where published_at is null and parked_at is null
        order by created_seq
        limit 1
        """, nativeQuery = true)
    java.util.Optional<Instant> findOldestPendingCreatedAt();

    /** Events the relay gave up on; they need a manual replay (runbook). */
    long countByPublishedAtIsNullAndParkedAtIsNotNull();
}
