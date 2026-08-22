package br.com.fabio.logisticagent.config;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationPredicate;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.observation.ServerRequestObservationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.support.ScheduledTaskObservationContext;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O predicate é o que impede o Langfuse de virar um mural de health check: o start.sh e o
 * webui batem em loop, e o @Scheduled do BackendHealthIndicator roda a cada 15s.
 */
class LangfuseObservabilityConfigTest {

    private final ObservationPredicate predicate = new LangfuseObservabilityConfig().skipHealthChecks();

    @Test
    void chatRequestIsObserved() {
        assertThat(predicate.test("http.server.requests", serverRequest("/api/chat"))).isTrue();
    }

    @Test
    void healthEndpointsAreNotObserved() {
        assertThat(predicate.test("http.server.requests", serverRequest("/api/chat/health"))).isFalse();
        assertThat(predicate.test("http.server.requests", serverRequest("/actuator/health"))).isFalse();
    }

    @Test
    void scheduledTasksAreNotObserved() throws NoSuchMethodException {
        Method check = BackendHealthIndicator.class.getDeclaredMethod("check");
        ScheduledTaskObservationContext context =
                new ScheduledTaskObservationContext(new BackendHealthIndicator(), check);

        assertThat(predicate.test("tasks.scheduled.execution", context)).isFalse();
    }

    @Test
    void securityFilterChainObservationsAreNotObserved() {
        assertThat(predicate.test("spring.security.filterchains", new Observation.Context())).isFalse();
        assertThat(predicate.test("spring.security.http.secured.filterchains", new Observation.Context())).isFalse();
    }

    @Test
    void otherObservationsPassThrough() {
        assertThat(predicate.test("spring.ai.chat.client", new Observation.Context())).isTrue();
    }

    private static ServerRequestObservationContext serverRequest(String path) {
        HttpServletRequest request = new MockHttpServletRequest("POST", path);
        return new ServerRequestObservationContext(request, new MockHttpServletResponse());
    }
}
