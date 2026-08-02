package com.jmj.trade.order;

import com.jmj.trade.inbox.InboxLedger;
import com.jmj.trade.notification.NotificationOutboxWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.annotation.UserConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay is opt-in: its beans exist only when {@code order.outbox.enabled=true}. The stub
 * dependencies never touch a database (bean construction alone is exercised), and a far-future
 * initial delay keeps the scheduler from firing a real sweep while the test context is open.
 */
class OrderOutboxConfigurationTest {

    private static final DataSource STUB_DATA_SOURCE = new AbstractDataSource() {
        @Override
        public Connection getConnection() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Connection getConnection(String username, String password) {
            throw new UnsupportedOperationException();
        }
    };

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> new JdbcTemplate(STUB_DATA_SOURCE))
            .withBean(PlatformTransactionManager.class, NoOpTransactionManager::new)
            .withBean(InboxLedger.class, () -> new InboxLedger(new JdbcTemplate(STUB_DATA_SOURCE)))
            .withBean(NotificationOutboxWriter.class,
                    () -> new NotificationOutboxWriter(new JdbcTemplate(STUB_DATA_SOURCE), new ObjectMapper()))
            .withConfiguration(UserConfigurations.of(OrderOutboxConfiguration.class));

    @Test
    void registersNoRelayBeansWhenThePropertyIsUnset() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(OrderOutboxProcessor.class);
            assertThat(context).doesNotHaveBean(OrderOutboxScheduler.class);
        });
    }

    @Test
    void registersBothProcessorsAndTheSchedulerWhenEnabled() {
        runner.withPropertyValues("order.outbox.enabled=true", "order.outbox.initial-delay=PT1H")
                .run(context -> {
                    assertThat(context.getBeansOfType(OrderOutboxProcessor.class)).hasSize(2);
                    assertThat(context).hasSingleBean(OrderOutboxScheduler.class);
                });
    }

    private static final class NoOpTransactionManager implements PlatformTransactionManager {

        @Override
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            return new SimpleTransactionStatus();
        }

        @Override
        public void commit(TransactionStatus status) {
        }

        @Override
        public void rollback(TransactionStatus status) {
        }
    }
}
