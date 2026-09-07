package br.com.fabio.logisticagent.tool;

import br.com.fabio.logisticagent.dto.render.IRenderableContent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * A visualização preparada nesta requisição, lida pelo ChatService depois da chamada ao modelo.
 *
 * <p>Escopo de requisição de propósito: com escopo maior, o render de uma requisição vazaria para
 * outra concorrente.
 */
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
