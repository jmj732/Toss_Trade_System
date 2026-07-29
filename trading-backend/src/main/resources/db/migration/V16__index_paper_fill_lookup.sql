-- Supports PaperPerformanceAnalyticsService's per-connection fill lookup: filtering
-- order_intents by (user_id, broker_connection_id), joining to broker_orders by
-- order_intent_id, and pulling the latest execution_snapshot per broker_order_id.
CREATE INDEX ix_order_intents_user_connection
    ON order_intents (user_id, broker_connection_id)
    WHERE user_id IS NOT NULL;

CREATE INDEX ix_broker_orders_order_intent
    ON broker_orders (order_intent_id);

CREATE INDEX ix_execution_snapshots_broker_order_latest
    ON execution_snapshots (broker_order_id, captured_at DESC, id DESC);
