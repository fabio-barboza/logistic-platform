package br.com.fabio.logisticagent.core.agent;

import br.com.fabio.logisticagent.core.domain.render.RenderableContent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class RenderHolder {

    private RenderableContent content;

    public void set(RenderableContent content) {
        this.content = content;
    }

    public RenderableContent get() {
        return content;
    }
}
