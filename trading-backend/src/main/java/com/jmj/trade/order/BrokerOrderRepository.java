package com.jmj.trade.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BrokerOrderRepository extends JpaRepository<BrokerOrder, UUID> {
    Optional<BrokerOrder> findByBrokerAccountIdAndBrokerOrderId(UUID brokerAccountId, String brokerOrderId);
}
