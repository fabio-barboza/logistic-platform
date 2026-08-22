package br.com.fabio.logisticagent.config;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.content.Content;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;
import org.springframework.util.CollectionUtils;

import java.util.stream.Collectors;

/**
 * Ajustes de observabilidade voltados ao Langfuse, que recebe os traces via OTLP.
 * Só entra em cena com {@code langfuse.enabled=true}; sem isso o agent roda sem tracing
 * e sem nenhuma dessas customizações.
 */
@Configuration
@ConditionalOnProperty(name = "langfuse.enabled", havingValue = "true")
public class LangfuseObservabilityConfig {

    /** Atributos que o Langfuse lê de um span para preencher input/output da observation. */
    private static final String GEN_AI_PROMPT = "gen_ai.prompt";
    private static final String GEN_AI_COMPLETION = "gen_ai.completion";
    private static final String OBSERVATION_INPUT = "langfuse.observation.input";
    private static final String OBSERVATION_OUTPUT = "langfuse.observation.output";

    /**
     * Health checks são chamados em loop pelo start.sh e pelo webui; virariam um trace
     * vazio por segundo no Langfuse. Dois caminhos geram esse ruído, e os dois precisam ser
     * cortados: a requisição HTTP que chega (ServerRequestObservationContext) e o
     * {@code @Scheduled} do BackendHealthIndicator, que o Spring observa por conta própria
     * como "tasks.scheduled.execution" — esse último produzia um trace de 5ms a cada 15s.
     * O Langfuse é para as conversas; nenhuma task agendada do agent tem valor lá.
     *
     * Terceiro corte, por nome: o Spring Security 6+ instrumenta o próprio filter chain
     * ("spring.security.filterchains" e "spring.security.http.secured.filterchains"), um
     * par before/after por requisição HTTP. O contexto é uma classe package-private de
     * {@code ObservationFilterChainDecorator} — não dá para filtrar por
     * {@code instanceof} como nos outros dois, daí o corte ser pelo prefixo do nome.
     */
    @Bean
    ObservationPredicate skipHealthChecks() {
        return (name, context) -> {
            if (name.startsWith("spring.security")) {
                return false;
            }
            if (context instanceof ServerRequestObservationContext serverContext) {
                String path = serverContext.getCarrier().getRequestURI();
                return !path.startsWith("/actuator") && !path.equals("/api/chat/health");
            }
            return !(context instanceof ScheduledTaskObservationContext);
        };
    }

    /**
     * O Spring AI só manda prompt e resposta para o log (via
     * {@code spring.ai.chat.observations.log-prompt}); o Langfuse lê de atributos do span.
     * Um ObservationFilter roda no stop da observation, antes do handler de tracing fechar
     * a span — daí os KeyValues virarem atributos. Um ObservationHandler no mesmo ponto
     * chegaria com a span já encerrada e as tags se perderiam.
     */
    @Bean
    ObservationFilter langfuseChatContentFilter() {
        return context -> {
            if (context instanceof ChatModelObservationContext chatContext) {
                String prompt = prompt(chatContext);
                if (!prompt.isEmpty()) {
                    context.addHighCardinalityKeyValue(KeyValue.of(GEN_AI_PROMPT, prompt));
                }
                String completion = completion(chatContext);
                if (!completion.isEmpty()) {
                    context.addHighCardinalityKeyValue(KeyValue.of(GEN_AI_COMPLETION, completion));
                }
            }
            else if (context instanceof ToolCallingObservationContext toolContext) {
                // Sem isso a tool aparece no Langfuse só como nome e duração: os argumentos
                // que o modelo montou e o retorno da API ficam de fora.
                context.addHighCardinalityKeyValue(
                        KeyValue.of(OBSERVATION_INPUT, toolContext.getToolCallArguments()));
                String result = toolContext.getToolCallResult();
                if (result != null && !result.isBlank()) {
                    context.addHighCardinalityKeyValue(KeyValue.of(OBSERVATION_OUTPUT, result));
                }
            }
            return context;
        };
    }

    private static String prompt(ChatModelObservationContext context) {
        if (context.getRequest() == null || CollectionUtils.isEmpty(context.getRequest().getInstructions())) {
            return "";
        }
        return context.getRequest().getInstructions().stream()
                .map(Content::getText)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private static String completion(ChatModelObservationContext context) {
        if (context.getResponse() == null || CollectionUtils.isEmpty(context.getResponse().getResults())) {
            return "";
        }
        return context.getResponse().getResults().stream()
                .map(generation -> generation.getOutput() != null ? generation.getOutput().getText() : null)
                .filter(text -> text != null && !text.isBlank())
                .collect(Collectors.joining("\n"));
    }
}
