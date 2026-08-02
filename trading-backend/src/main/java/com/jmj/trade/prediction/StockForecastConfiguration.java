package com.jmj.trade.prediction;

import com.jmj.trade.analysis.StockAnalysisWorkflowService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;

@Configuration(proxyBeanMethods = false)
public class StockForecastConfiguration {

    @Bean
    PredictionModelRegistryService predictionModelRegistryService(JdbcTemplate jdbcTemplate) {
        return new PredictionModelRegistryService(jdbcTemplate);
    }

    @Bean
    StockForecastService stockForecastService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            StockAnalysisWorkflowService analyses,
            ObjectProvider<AnalysisPredictionService> predictions,
            PredictionModelRegistryService registry,
            @Value("${analysis.service.base-url:http://localhost:8000}") String baseUrl,
            @Value("${analysis.service.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${analysis.service.read-timeout:PT5S}") Duration readTimeout
    ) {
        return new StockForecastService(
                jdbcTemplate, transactionManager, objectMapper, analyses, predictions, registry,
                baseUrl, connectTimeout, readTimeout);
    }
}
