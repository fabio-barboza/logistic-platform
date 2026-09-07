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

    /**
     * Além de logar, registra a chamada no {@link ToolCallHolder} da requisição — é dali que o
     * ChatService descobre que o modelo respondeu com dados sem ter consultado nada.
     * O holder vem por {@link ObjectProvider} porque este handler é singleton e roda também fora de
     * requisição (o eval sobe o contexto sem servlet); nesse caso não há holder e só o log acontece.
     */
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
            /**
             * Guarda as linhas do executeQuery para o render montar a visualização a partir delas.
             * Interceptar aqui, e não na tool, é o que mantém a logistic-api fora do assunto: o
             * retorno já passa por este handler para ir ao log.
             */
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
                    // Fora de requisição não há holder — o proxy do escopo estoura ao ser tocado.
                    // Registrar é acessório; o log, que é a garantia de diagnóstico, já aconteceu.
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
