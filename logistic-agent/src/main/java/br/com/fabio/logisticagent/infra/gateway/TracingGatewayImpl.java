package br.com.fabio.logisticagent.infra.gateway;

import br.com.fabio.logisticagent.core.gateway.TracingGateway;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
public class TracingGatewayImpl implements TracingGateway {

    private final ObjectProvider<Tracer> tracerProvider;

    public TracingGatewayImpl(ObjectProvider<Tracer> tracerProvider) {
        this.tracerProvider = tracerProvider;
    }

    @Override
    public void tag(String key, String value) {
        Tracer tracer = tracerProvider.getIfAvailable();
        Span span = tracer != null ? tracer.currentSpan() : null;
        if (span != null && value != null) {
            span.tag(key, value);
        }
    }
}
