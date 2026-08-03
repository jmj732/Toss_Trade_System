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
    ForecastQualityMonitoringService forecastQualityMonitoringService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            @Value("${prediction.forecast-quality.minimum-sample-count:10}") int minimumSampleCount
    ) {
        return new ForecastQualityMonitoringService(jdbcTemplate, objectMapper, minimumSampleCount);
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

    @Bean
    StockAnalysisGeminiExplainService stockAnalysisGeminiExplainService(
            JdbcTemplate jdbcTemplate,
            PlatformTransactionManager transactionManager,
            ObjectMapper objectMapper,
            @Value("${gemini.api-key:}") String apiKey,
            @Value("${gemini.model-id:gemini-2.5-flash}") String modelId,
            @Value("${gemini.prompt-version:stock-analysis-explain-v1}") String promptVersion,
            @Value("${gemini.base-url:https://generativelanguage.googleapis.com/v1beta}") String baseUrl,
            @Value("${gemini.connect-timeout:PT2S}") Duration connectTimeout,
            @Value("${gemini.read-timeout:PT10S}") Duration readTimeout
    ) {
        return new StockAnalysisGeminiExplainService(
                jdbcTemplate, transactionManager, objectMapper, apiKey, modelId, promptVersion,
                baseUrl, connectTimeout, readTimeout);
    }
}
