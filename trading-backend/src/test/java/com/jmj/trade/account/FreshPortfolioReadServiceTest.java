package com.jmj.trade.account;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FreshPortfolioReadServiceTest {

    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID CONNECTION_ID = UUID.randomUUID();
    private static final Instant OBSERVED_AT = Instant.parse("2026-08-09T00:00:00Z");

    @Test
    void synchronizesEvenWhenPersistedSnapshotIsWithinMaxAge() {
        var reads = mock(PortfolioReadService.class);
        var sync = mock(AccountSyncService.class);
        var syncs = provider(sync);
        var after = view(OBSERVED_AT.plusSeconds(1), false, null);
        when(reads.read(USER_ID, CONNECTION_ID)).thenReturn(after);

        var service = new FreshPortfolioReadService(reads, syncs);

        assertThat(service.read(USER_ID, CONNECTION_ID)).isSameAs(after);

        var order = inOrder(sync, reads);
        order.verify(sync).sync(USER_ID, CONNECTION_ID);
        order.verify(reads).read(USER_ID, CONNECTION_ID);
    }

    @Test
    void brokerFailureReturnsLastSnapshotMarkedAsLiveSyncFailed() {
        var reads = mock(PortfolioReadService.class);
        var sync = mock(AccountSyncService.class);
        var old = view(OBSERVED_AT, false, null);
        when(reads.read(USER_ID, CONNECTION_ID)).thenReturn(old);
        doAnswer(invocation -> {
            throw new IllegalStateException("Toss unavailable");
        }).when(sync).sync(USER_ID, CONNECTION_ID);

        var service = new FreshPortfolioReadService(reads, provider(sync));

        var result = service.read(USER_ID, CONNECTION_ID);

        assertThat(result).isNotSameAs(old);
        assertThat(result.syncRunId()).isEqualTo(old.syncRunId());
        assertThat(result.stale()).isTrue();
        assertThat(result.staleReason()).isEqualTo("LIVE_SYNC_FAILED");
        verify(reads).read(USER_ID, CONNECTION_ID);
    }

    @Test
    void concurrentReadsShareOneBrokerSync() throws Exception {
        var reads = mock(PortfolioReadService.class);
        var sync = mock(AccountSyncService.class);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var view = view(OBSERVED_AT, false, null);
        when(reads.read(USER_ID, CONNECTION_ID)).thenReturn(view);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(sync).sync(USER_ID, CONNECTION_ID);
        var syncs = provider(sync);

        var service = new FreshPortfolioReadService(reads, syncs);
        var first = new CompletableFuture<PortfolioReadService.PortfolioView>();
        var second = new CompletableFuture<PortfolioReadService.PortfolioView>();
        var firstReader = new Thread(() -> completeRead(first, service), "first-portfolio-reader");
        var secondReader = new Thread(() -> completeRead(second, service), "second-portfolio-reader");
        firstReader.start();
        try {
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            secondReader.start();
            assertThat(awaitSharedRefreshJoin(secondReader, 5, TimeUnit.SECONDS))
                    .as("second reader should be waiting in the in-flight CompletableFuture join")
                    .isTrue();
            verify(sync, times(1)).sync(USER_ID, CONNECTION_ID);
        } finally {
            release.countDown();
            firstReader.join(TimeUnit.SECONDS.toMillis(5));
            secondReader.join(TimeUnit.SECONDS.toMillis(5));
        }

        assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(view);
        assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(view);
        verify(sync, times(1)).sync(USER_ID, CONNECTION_ID);

        assertThat(service.read(USER_ID, CONNECTION_ID)).isSameAs(view);
        verify(sync, times(2)).sync(USER_ID, CONNECTION_ID);
    }

    private static void completeRead(
            CompletableFuture<PortfolioReadService.PortfolioView> result,
            FreshPortfolioReadService service
    ) {
        try {
            result.complete(service.read(USER_ID, CONNECTION_ID));
        } catch (Throwable failure) {
            result.completeExceptionally(failure);
        }
    }

    private static boolean awaitSharedRefreshJoin(Thread reader, long timeout, TimeUnit unit) {
        var deadline = System.nanoTime() + unit.toNanos(timeout);
        do {
            if (isWaitingInRefreshJoin(reader)) return true;
            Thread.yield();
        } while (System.nanoTime() < deadline);
        return isWaitingInRefreshJoin(reader);
    }

    private static boolean isWaitingInRefreshJoin(Thread reader) {
        if (reader.getState() != Thread.State.WAITING) return false;
        var inRefresh = false;
        var inFutureJoin = false;
        for (var frame : reader.getStackTrace()) {
            inRefresh |= frame.getClassName().equals(FreshPortfolioReadService.class.getName())
                    && frame.getMethodName().equals("refresh");
            inFutureJoin |= frame.getClassName().equals(CompletableFuture.class.getName())
                    && (frame.getMethodName().equals("waitingGet") || frame.getMethodName().equals("join"));
        }
        return inRefresh && inFutureJoin;
    }

    private static PortfolioReadService.PortfolioView view(
            Instant completedAt,
            boolean stale,
            String staleReason
    ) {
        return new PortfolioReadService.PortfolioView(
                UUID.randomUUID(), completedAt, stale, staleReason, false,
                List.of(), List.of(), account(), List.of(), Map.of());
    }

    private static PortfolioReadService.AccountView account() {
        return new PortfolioReadService.AccountView(
                "GENERAL", "****5678", Map.of("USD", BigDecimal.TEN),
                Map.of("USD", BigDecimal.TEN), Map.of(), Map.of(), Map.of(), Map.of(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, OBSERVED_AT);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<AccountSyncService> provider(AccountSyncService sync) {
        var provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sync);
        return provider;
    }
}
