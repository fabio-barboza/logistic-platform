package br.com.fabio.logisticagent.tool;

import br.com.fabio.logisticagent.dto.render.IRenderableContent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class RenderHolder {

    private IRenderableContent content;

    public void set(IRenderableContent content) {
        this.content = content;
    }

    public IRenderableContent get() {
        return content;
    }
}
