package com.jmj.trade.broker.toss;

import com.jmj.trade.broker.AccountCapacitySnapshot;
import com.jmj.trade.broker.AccountSnapshot;
import com.jmj.trade.broker.BrokerAccountRef;
import com.jmj.trade.broker.BrokerAccountView;
import com.jmj.trade.broker.BrokerAdapter;
import com.jmj.trade.broker.BrokerCallMetadata;
import com.jmj.trade.broker.BrokerConnectionRef;
import com.jmj.trade.broker.BrokerErrorCategory;
import com.jmj.trade.broker.BrokerException;
import com.jmj.trade.broker.BrokerOrderAck;
import com.jmj.trade.broker.BrokerOrderGroup;
import com.jmj.trade.broker.BrokerOrderPort;
import com.jmj.trade.broker.BrokerOrderRequest;
import com.jmj.trade.broker.BrokerOrderView;
import com.jmj.trade.broker.BrokerResponse;
import com.jmj.trade.broker.BrokerOrderLifecycle;
import com.jmj.trade.broker.BrokerOrderModification;
import com.jmj.trade.broker.BrokerOrderSide;
import com.jmj.trade.broker.BrokerOrderType;
import com.jmj.trade.broker.Currency;
import com.jmj.trade.broker.Position;
import com.jmj.trade.broker.Quote;
import com.jmj.trade.broker.SellableQuantitySnapshot;

import java.util.List;
import java.util.Objects;

import static com.jmj.trade.broker.BrokerOrderPort.ack;

final class TossInvestBrokerAdapter implements BrokerAdapter, BrokerOrderPort {

    private final TossApiClient apiClient;
    private final TossResponseMapper mapper;

    TossInvestBrokerAdapter(TossApiClient apiClient, TossResponseMapper mapper) {
        this.apiClient = Objects.requireNonNull(apiClient, "apiClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    @Override
    public BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection) {
        mapper.requireToss(connection);
        var response = apiClient.getAccounts(connection.brokerConnectionId());
        var accounts = List.copyOf(response.value().stream()
                .map(account -> mapper.account(connection, account))
                .toList());
        return new BrokerResponse<>(accounts, response.metadata());
    }

    @Override
    public BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account) {
        mapper.requireToss(account);
        var response = apiClient.getHoldings(account.brokerConnectionId(), account.brokerAccountId());
        return new BrokerResponse<>(mapper.accountSnapshot(account, response.value(), response.metadata()), response.metadata());
    }

    @Override
    public BrokerResponse<List<Position>> getPositions(BrokerAccountRef account) {
        mapper.requireToss(account);
        var response = apiClient.getHoldings(account.brokerConnectionId(), account.brokerAccountId());
        return new BrokerResponse<>(mapper.positions(account, response.value(), response.metadata()), response.metadata());
    }

    @Override
    public BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol) {
        mapper.requireToss(connection);
        var normalized = mapper.normalizeSymbol(symbol);
        var response = apiClient.getPrices(connection.brokerConnectionId(), normalized);
        return new BrokerResponse<>(mapper.quote(connection, normalized, response.value(), response.metadata()), response.metadata());
    }

    @Override
    public BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(BrokerAccountRef account, Currency currency) {
        mapper.requireToss(account);
        var response = apiClient.getBuyingPower(account.brokerConnectionId(), account.brokerAccountId(), currency.name());
        return new BrokerResponse<>(mapper.capacity(account, currency, response.value(), response.metadata()), response.metadata());
    }

    @Override
    public BrokerResponse<SellableQuantitySnapshot> getSellableQuantity(BrokerAccountRef account, String symbol) {
        mapper.requireToss(account);
        var normalized = mapper.normalizeSymbol(symbol);
        try {
            var response = apiClient.getSellableQuantity(
                    account.brokerConnectionId(), account.brokerAccountId(), normalized);
            return new BrokerResponse<>(
                    mapper.sellableQuantity(account, normalized, response.value(), response.metadata()),
                    response.metadata());
        } catch (BrokerException exception) {
            if (!fallsBackToUnknown(exception)) {
                throw exception;
            }
            var metadata = BrokerOrderPort.localMetadata();
            return new BrokerResponse<>(
                    SellableQuantitySnapshot.unknown(account, normalized, metadata.observedAt()),
                    metadata);
        }
    }

    private static boolean fallsBackToUnknown(BrokerException exception) {
        return switch (exception.category()) {
            case AUTHORIZATION, RATE_LIMITED, NOT_FOUND, VALIDATION, BROKER_UNAVAILABLE,
                    NETWORK, TEMPORARY, CONTRACT, UNKNOWN -> true;
            default -> false;
        };
    }

    @Override
    public BrokerResponse<BrokerOrderAck> placeOrder(
            BrokerAccountRef account,
            BrokerOrderRequest request,
            String idempotencyKey
    ) {
        mapper.requireToss(account);
        try {
            var response = apiClient.createOrder(account, request, idempotencyKey);
            if (response == null) {
                return ack(BrokerOrderAck.unknown(null, idempotencyKey));
            }
            var result = response.value();
            if (result == null || result.orderId() == null || result.orderId().isBlank()) {
                return ack(BrokerOrderAck.unknown(null, idempotencyKey));
            }
            return new BrokerResponse<>(
                    BrokerOrderAck.accepted(result.orderId(), result.clientOrderId()), response.metadata());
        } catch (BrokerException exception) {
            return new BrokerResponse<>(failure(exception, null, idempotencyKey, false), BrokerOrderPort.localMetadata());
        }
    }

    @Override
    public BrokerResponse<BrokerOrderAck> cancelOrder(BrokerAccountRef account, String brokerOrderId) {
        mapper.requireToss(account);
        try {
            var response = apiClient.cancelOrder(account, brokerOrderId);
            if (response == null) {
                return ack(BrokerOrderAck.unknown(brokerOrderId, null));
            }
            var result = response.value();
            if (result == null || result.orderId() == null || result.orderId().isBlank()) {
                return new BrokerResponse<>(BrokerOrderAck.unknown(null, null), response.metadata());
            }
            return new BrokerResponse<>(BrokerOrderAck.accepted(result.orderId(), null), response.metadata());
        } catch (BrokerException exception) {
            return new BrokerResponse<>(failure(exception, brokerOrderId, null, true), BrokerOrderPort.localMetadata());
        }
    }

    @Override
    public BrokerResponse<BrokerOrderView> getOrder(BrokerAccountRef account, String brokerOrderId) {
        mapper.requireToss(account);
        var response = apiClient.getOrder(account, brokerOrderId);
        if (response == null) {
            throw new BrokerException(BrokerErrorCategory.CONTRACT, 200, null, null, null, false,
                    "Toss order detail response was missing");
        }
        return new BrokerResponse<>(mapper.order(response.value()), response.metadata());
    }

    @Override
    public BrokerResponse<List<BrokerOrderView>> getOrders(BrokerAccountRef account, BrokerOrderGroup group) {
        mapper.requireToss(account);
        var all = new java.util.ArrayList<BrokerOrderView>();
        String cursor = null;
        BrokerCallMetadata metadata = null;
        do {
            var response = apiClient.getOrders(account, group, cursor);
            if (response == null) {
                throw new BrokerException(BrokerErrorCategory.CONTRACT, 200, null, null, null, false,
                        "Toss order list response was missing");
            }
            metadata = response.metadata();
            var page = response.value();
            if (page == null || page.orders() == null || page.hasNext() == null) {
                throw new BrokerException(BrokerErrorCategory.CONTRACT, 200, null, null, null, false,
                        "Toss order list response was invalid");
            }
            all.addAll(page.orders().stream().map(mapper::order).toList());
            if (page.hasNext() && (page.nextCursor() == null || page.nextCursor().isBlank())) {
                throw new BrokerException(BrokerErrorCategory.CONTRACT, 200, null, null, null, false,
                        "Toss order list cursor was missing");
            }
            cursor = page.hasNext() ? page.nextCursor() : null;
        } while (cursor != null);
        return new BrokerResponse<>(List.copyOf(all), Objects.requireNonNull(metadata, "metadata"));
    }

    @Override
    public BrokerResponse<BrokerOrderAck> modifyOrder(BrokerAccountRef account, BrokerOrderModification modification) {
        mapper.requireToss(account);
        if (modification.changesQuantity()) {
            return ack(BrokerOrderAck.rejected(BrokerErrorCategory.CONTRACT, modification.brokerOrderId(), null));
        }
        try {
            var response = apiClient.modifyOrder(account, modification);
            if (response == null) {
                return ack(BrokerOrderAck.unknown(modification.brokerOrderId(), null));
            }
            var result = response.value();
            if (result == null || result.orderId() == null || result.orderId().isBlank()) {
                return new BrokerResponse<>(BrokerOrderAck.unknown(null, null), response.metadata());
            }
            return new BrokerResponse<>(BrokerOrderAck.accepted(result.orderId(), null), response.metadata());
        } catch (BrokerException exception) {
            return new BrokerResponse<>(failure(exception, modification.brokerOrderId(), null, true), BrokerOrderPort.localMetadata());
        }
    }

    private static BrokerOrderAck failure(
            BrokerException exception,
            String brokerOrderId,
            String idempotencyKey,
            boolean operation) {
        var explicitRejection = exception.category() == BrokerErrorCategory.INVALID_REQUEST
                || exception.category() == BrokerErrorCategory.VALIDATION
                || operation && exception.category() == BrokerErrorCategory.NOT_FOUND;
        return explicitRejection
                ? BrokerOrderAck.rejected(exception.category(), brokerOrderId, idempotencyKey)
                : BrokerOrderAck.unknown(brokerOrderId, idempotencyKey);
    }
}
