-- BC-2: 대시보드는 보유 심볼 전체의 "가장 최근 성공한 종목 분석 실행"을 한 번의 DISTINCT ON
-- 쿼리로 읽는다(심볼당 한 번 도는 N+1 금지). 그 쿼리의 필터와 정렬을 그대로 만족시키는
-- 부분 인덱스가 없으면 보유 종목이 늘수록 stock_analysis_runs 전체 스캔으로 번진다.
CREATE INDEX ix_stock_analysis_run_latest_success
    ON stock_analysis_runs (user_id, symbol, completed_at DESC, id DESC)
    WHERE status = 'SUCCEEDED';
