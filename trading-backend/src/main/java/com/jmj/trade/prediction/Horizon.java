package com.jmj.trade.prediction;

public enum Horizon {
    D1(1),
    D5(5),
    D20(20);

    private final int days;

    Horizon(int days) {
        this.days = days;
    }

    public int days() {
        return days;
    }
}
