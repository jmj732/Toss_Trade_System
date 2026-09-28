package com.jmj.trade.notification;

@FunctionalInterface
interface TelegramMessageSender {
    void send(String message);
}
