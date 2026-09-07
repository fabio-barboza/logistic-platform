package br.com.fabio.logisticagent.service;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;

public interface IConversationStateRepository extends JpaRepository<ConversationStateEntity, String> {

    @Modifying
    @Query("DELETE FROM ConversationStateEntity c WHERE c.updatedAt < :limit")
    int deleteStale(@Param("limit") Instant limit);
}
