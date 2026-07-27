package com.jmj.trade;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class TradingBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(TradingBackendApplication.class, args);
    }
}
