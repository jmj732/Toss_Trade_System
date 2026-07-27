package com.jmj.trade.order;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

public interface OrderIntentRepository extends JpaRepository<OrderIntent, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select intent from OrderIntent intent where intent.id = :id")
    Optional<OrderIntent> findByIdForUpdate(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select intent from OrderIntent intent
             where intent.id = :id
               and intent.userId = :userId
               and intent.brokerConnectionId = :connectionId
            """)
    Optional<OrderIntent> findOwnedByIdForUpdate(UUID id, UUID userId, UUID connectionId);
}
