package br.com.fabio.logisticagent.tool;

import br.com.fabio.logisticagent.dto.render.RenderableContent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class RenderHolder {

    private RenderableContent content;
    private String lastError;

    public void set(RenderableContent content) {
        this.content = content;
        this.lastError = null;
    }

    public RenderableContent get() {
        return content;
    }

    /**
     * Guarda a crítica devolvida por uma chamada de render inválida. O modelo recebe a mesma
     * mensagem como retorno da tool e deveria corrigir e chamar de novo — quando não corrige,
     * é isso que o ChatService usa para não deixar a resposta afirmar um gráfico que não existe.
     */
    public void setError(String lastError) {
        this.lastError = lastError;
    }

    public String getError() {
        return lastError;
    }
}
