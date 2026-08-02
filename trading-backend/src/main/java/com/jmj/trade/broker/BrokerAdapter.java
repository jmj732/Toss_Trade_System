package com.jmj.trade.broker;

import java.util.List;

public interface BrokerAdapter {

    BrokerResponse<List<BrokerAccountView>> getAccounts(BrokerConnectionRef connection);

    BrokerResponse<AccountSnapshot> getAccount(BrokerAccountRef account);

    BrokerResponse<List<Position>> getPositions(BrokerAccountRef account);

    BrokerResponse<Quote> getQuote(BrokerConnectionRef connection, String symbol);

    BrokerResponse<AccountCapacitySnapshot> getAccountCapacity(BrokerAccountRef account, Currency currency);

    /**
     * 종목별 매도 가능 수량 조회(플랜 원장 B3; SPEC:881,1077). 매도 주문 제출 직전 재검증이
     * 사용하는 읽기 전용 조회다. 브로커가 값을 제공하지 않으면 {@link SellableQuantitySnapshot}
     * 이 {@code UNKNOWN} 을 담아 반환하며, 조용히 0 으로 간주하지 않는다(SPEC:1078).
     */
    BrokerResponse<SellableQuantitySnapshot> getSellableQuantity(BrokerAccountRef account, String symbol);
}
