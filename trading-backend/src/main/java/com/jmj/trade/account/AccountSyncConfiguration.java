package com.jmj.trade.account;

import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.notification.NotificationOutboxWriter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;

/**
 * Owns the account-sync bean wiring ({@code accountSyncTransactions}, {@code accountSyncService}).
 * It shares the credential vault's activation property so the bean set stays byte-identical to when
 * this wiring lived in {@code CredentialVaultConfiguration}; a follow-up delta can drop the
 * condition once account sync no longer keys off credential availability.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "broker.credentials", name = "enabled", havingValue = "true")
public class AccountSyncConfiguration {

    @Bean
    AccountSyncTransactions accountSyncTransactions(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            @Value("${portfolio.sync.stale-after:PT15M}") Duration staleAfter,
            NotificationOutboxWriter notificationOutboxWriter
    ) {
        return new AccountSyncTransactions(
                jdbcTemplate,
                new TransactionTemplate(transactionManager),
                staleAfter,
                notificationOutboxWriter);
    }

    @Bean
    AccountSyncService accountSyncService(
            AccountSyncTransactions transactions,
            BrokerAdapter brokerAdapter
    ) {
        return new AccountSyncService(transactions, brokerAdapter);
    }
}
