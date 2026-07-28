package com.jmj.trade.migrate;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * One-shot migration entrypoint for staging/production deploys with multiple backend replicas:
 * runs Flyway (via {@code DataSourceAutoConfiguration} + {@code FlywayAutoConfiguration} against
 * the same {@code spring.datasource.*} properties the app itself uses) and exits, without
 * booting the web server or any of this application's own beans. It lives in its own package so
 * {@code @SpringBootApplication}'s component scan — rooted here, not at {@code com.jmj.trade} —
 * never picks up {@link com.jmj.trade.security.SecurityConfiguration} or anything else that
 * requires a servlet web context.
 */
@SpringBootApplication
public class MigrateOnlyApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(MigrateOnlyApplication.class)
                .web(WebApplicationType.NONE)
                .run(args)
                .close();
    }
}
