package br.com.fabio.logisticagent.confirm;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface IPendingActionRepository extends JpaRepository<PendingAction, String> {

    Optional<PendingAction> findByIdAndSessionIdAndCreatedAtAfter(
            String id, String sessionId, Instant limit);

    /**
     * Delete em massa (não carrega a entidade) para o consumo único ser decidido pelo banco: quem
     * receber 0 linhas perdeu a corrida.
     */
    @Modifying
    @Query("DELETE FROM PendingAction a WHERE a.id = :id AND a.sessionId = :sessionId")
    int deleteConsuming(@Param("id") String id, @Param("sessionId") String sessionId);

    @Modifying
    @Query("DELETE FROM PendingAction a WHERE a.createdAt < :limit")
    int deleteExpired(@Param("limit") Instant limit);
}
