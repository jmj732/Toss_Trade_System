-- 플랜 원장 B3: 종목별 매도 가능 수량(sellable quantity)을 보유 수량(holdings)과 분리 저장한다
-- (SPEC:1077). 매도 주문 제출 직전 재검증이 이 값을 읽어 판정한다.
--
-- NULL 은 "브로커가 매도 가능 수량을 제공하지 않음(UNKNOWN)" 을 뜻하며, 0(확정된 매도 불가)과
-- 구분된다(SPEC:1078). UNKNOWN 은 재검증에서 fail-closed 로 차단 사유가 된다. 컬럼은 nullable 이라
-- 기존 append-only 스냅샷 행과 호환되며, 실거래 sellable 조회는 E6 에서 채운다.
ALTER TABLE position_snapshots
    ADD COLUMN sellable_quantity NUMERIC
        CHECK (sellable_quantity IS NULL OR sellable_quantity >= 0);
