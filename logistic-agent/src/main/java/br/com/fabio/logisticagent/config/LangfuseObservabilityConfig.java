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

@Configuration
@ConditionalOnProperty(name = "langfuse.enabled", havingValue = "true")
public class LangfuseObservabilityConfig {

    private static final String GEN_AI_PROMPT = "gen_ai.prompt";
    private static final String GEN_AI_COMPLETION = "gen_ai.completion";
    private static final String OBSERVATION_INPUT = "langfuse.observation.input";
    private static final String OBSERVATION_OUTPUT = "langfuse.observation.output";

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
