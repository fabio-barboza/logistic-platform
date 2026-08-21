package br.com.fabio.logisticagent.eval;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Set;

/**
 * Registra, só no eval, as tools que o modelo chamou.
 *
 * <p>A captura é por {@link ObservationHandler}, e não por um decorator de
 * {@code ToolCallbackProvider}, porque as tools de escrita não executam mais: elas são embrulhadas
 * pela confirmação, que registra a pendência e devolve texto sem delegar. Um decorator por dentro
 * dessa camada nunca seria chamado, e o eval veria "nenhuma tool chamada" onde o modelo chamou
 * createVehicle corretamente. A observação envolve a chamada que o Spring AI faz, qualquer que
 * seja a decoração — é o mesmo caminho pelo qual o ToolCallLoggingConfig loga em produção.
 */
@TestConfiguration
public class EvalTestConfig {

    /** Tools locais do agent. O dataset mede escolha de tool MCP; render é avaliado à parte. */
    private static final Set<String> LOCAL_TOOLS = Set.of("renderChart", "renderTable");

    @Bean
    ToolCallRecorder toolCallRecorder() {
        return new ToolCallRecorder();
    }

    @Bean
    ObservationHandler<ToolCallingObservationContext> evalToolCallRecordingHandler(ToolCallRecorder recorder) {
        return new ObservationHandler<>() {

            @Override
            public boolean supportsContext(Observation.Context context) {
                return context instanceof ToolCallingObservationContext;
            }

            @Override
            public void onStop(ToolCallingObservationContext context) {
                record(context);
            }

            @Override
            public void onError(ToolCallingObservationContext context) {
                record(context);
            }

            private void record(ToolCallingObservationContext context) {
                String name = context.getToolDefinition().name();
                if (!LOCAL_TOOLS.contains(name)) {
                    recorder.record(name, context.getToolCallArguments());
                }
            }
        };
    }
}
