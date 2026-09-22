package br.com.fabio.logisticagent.eval;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.util.Set;

@TestConfiguration
public class EvalTestConfig {

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
