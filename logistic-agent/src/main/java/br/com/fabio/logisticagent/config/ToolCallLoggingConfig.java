package br.com.fabio.logisticagent.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registra no log toda tool que o modelo chamou, com argumentos e retorno.
 * <p>
 * Sem isso não há como distinguir "o modelo chamou createVehicle e a API falhou" de "o modelo
 * disse que cadastrou sem nunca ter chamado a tool" — as duas terminam na mesma frase para o
 * usuário. O Langfuse mostra isso, mas é opcional e vem desligado por padrão; este log não.
 */
@Configuration
public class ToolCallLoggingConfig {

    private static final Logger log = LoggerFactory.getLogger(ToolCallLoggingConfig.class);

    /** Retorno de busca pode ter dezenas de registros; no log só interessa o começo. */
    private static final int MAX_RESULT_CHARS = 300;

    @Bean
    ObservationHandler<ToolCallingObservationContext> toolCallLoggingHandler() {
        return new ObservationHandler<>() {

            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof ToolCallingObservationContext;
            }

            @Override
            public void onStop(ToolCallingObservationContext context) {
                log.info("Tool chamada: {} args={} result={}",
                        context.getToolDefinition().name(),
                        context.getToolCallArguments(),
                        truncate(context.getToolCallResult()));
            }

            @Override
            public void onError(ToolCallingObservationContext context) {
                log.warn("Tool falhou: {} args={}",
                        context.getToolDefinition().name(),
                        context.getToolCallArguments(),
                        context.getError());
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
