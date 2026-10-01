package com.jmj.trade.investment;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.env.PropertySource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class InvestmentDataPropertiesBindingTest {

    private static final String CONSENSUS_STALE_AFTER =
            "INVESTMENT_CONSENSUS_STALE_AFTER";

    @Test
    void deploymentDefaultsUseParseableConsensusStalenessDuration() throws IOException {
        var root = Path.of("..");
        var compose = loadYaml(root.resolve("compose.yaml"));
        assertPlaceholderDuration(compose,
                "services.backend.environment.INVESTMENT_CONSENSUS_STALE_AFTER", ":-");

        var application = loadYaml(Path.of("src/main/resources/application.yml"));
        assertPlaceholderDuration(application,
                "investment.data.consensus-stale-after", ":");

        var envExample = Files.readString(root.resolve(".env.example"));
        var line = envExample.lines()
                .filter(value -> value.startsWith(CONSENSUS_STALE_AFTER + "="))
                .findFirst()
                .orElseThrow();
        assertThat(Duration.parse(line.substring(line.indexOf('=') + 1)))
                .isEqualTo(Duration.ofDays(10));
    }

    private static PropertySource<?> loadYaml(Path path) throws IOException {
        List<PropertySource<?>> sources = new YamlPropertySourceLoader()
                .load(path.getFileName().toString(), new FileSystemResource(path));
        assertThat(sources).isNotEmpty();
        return sources.getFirst();
    }

    private static void assertPlaceholderDuration(
            PropertySource<?> source, String propertyName, String separator) {
        var expression = source.getProperty(propertyName).toString();
        assertThat(expression).startsWith("${").endsWith("}");
        var body = expression.substring(2, expression.length() - 1);
        var separatorIndex = body.indexOf(separator);
        assertThat(separatorIndex).isGreaterThanOrEqualTo(0);
        var defaultValue = body.substring(separatorIndex + separator.length());
        assertThat(Duration.parse(defaultValue)).isEqualTo(Duration.ofDays(10));
    }
}
