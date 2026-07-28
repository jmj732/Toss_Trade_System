package com.jmj.trade.refresh;

record ScheduledRefreshResult(boolean lockAcquired, int refreshed, int failed, int skipped) {

    static ScheduledRefreshResult notAcquired() {
        return new ScheduledRefreshResult(false, 0, 0, 0);
    }
}
