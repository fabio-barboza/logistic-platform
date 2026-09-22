package br.com.fabio.logisticagent.config;

import br.com.fabio.logisticagent.tool.QueryResultHolder;
import br.com.fabio.logisticagent.tool.ToolCallHolder;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ToolCallLoggingConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolCallLoggingConfig.class);

    private static final int MAX_RESULT_CHARS = 300;

    @Bean
    ObservationHandler<ToolCallingObservationContext> toolCallLoggingHandler(
            ObjectProvider<ToolCallHolder> toolCallHolderProvider,
            ObjectProvider<QueryResultHolder> queryResultHolderProvider) {
        return new ObservationHandler<>() {

            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof ToolCallingObservationContext;
            }

            @Override
            public void onStop(ToolCallingObservationContext context) {
                register(context.getToolDefinition().name());
                registerQueryResult(context.getToolDefinition().name(), context.getToolCallResult());
                log.info("Tool chamada: {} args={} result={}",
                        context.getToolDefinition().name(),
                        context.getToolCallArguments(),
                        truncate(context.getToolCallResult()));
            }

            @Override
            public void onError(ToolCallingObservationContext context) {
                register(context.getToolDefinition().name());
                log.warn("Tool falhou: {} args={}",
                        context.getToolDefinition().name(),
                        context.getToolCallArguments(),
                        context.getError());
            }

            private void registerQueryResult(String toolName, String result) {
                if (toolName == null || !toolName.endsWith("executeQuery")) {
                    return;
                }
                try {
                    queryResultHolderProvider.getObject().register(result);
                } catch (RuntimeException e) {
                    log.debug("Sem QueryResultHolder nesta execução: {}", e.getMessage());
                }
            }

            private void register(String toolName) {
                try {
                    toolCallHolderProvider.getObject().register(toolName);
                } catch (RuntimeException e) {

                    log.debug("Sem ToolCallHolder nesta execução: {}", e.getMessage());
                }
            }
        };
    }

    private static String truncate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= MAX_RESULT_CHARS
                ? value
                : value.substring(0, MAX_RESULT_CHARS) + "... (" + value.length() + " chars)";
    }
}
