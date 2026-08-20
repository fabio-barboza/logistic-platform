package br.com.fabio.logisticagent.tool;

import br.com.fabio.logisticagent.dto.render.RenderableContent;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

@Component
@RequestScope
public class RenderHolder {

    private RenderableContent content;
    private String lastError;
    private int rejections;

    public void set(RenderableContent content) {
        this.content = content;
        this.lastError = null;
        this.rejections = 0;
    }

    public RenderableContent get() {
        return content;
    }

    /**
     * Guarda a crítica devolvida por uma chamada de render inválida. O modelo recebe a mesma
     * mensagem como retorno da tool e deveria corrigir e chamar de novo — quando não corrige,
     * é isso que o ChatService usa para não deixar a resposta afirmar um gráfico que não existe.
     */
    public int registerRejection(String lastError) {
        this.lastError = lastError;
        return ++this.rejections;
    }

    public int rejections() {
        return rejections;
    }

    public String getError() {
        return lastError;
    }
}
