package br.com.fabio.logisticagent.infra.repository;

import br.com.fabio.logisticagent.infra.entity.PendingActionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface PendingActionRepository extends JpaRepository<PendingActionEntity, String> {

    Optional<PendingActionEntity> findByIdAndSessionIdAndCreatedAtAfter(
            String id, String sessionId, Instant limit);

    @Modifying
    @Query("DELETE FROM PendingActionEntity a WHERE a.id = :id AND a.sessionId = :sessionId")
    int deleteConsuming(@Param("id") String id, @Param("sessionId") String sessionId);

    @Modifying
    @Query("DELETE FROM PendingActionEntity a WHERE a.createdAt < :limit")
    int deleteExpired(@Param("limit") Instant limit);
}
