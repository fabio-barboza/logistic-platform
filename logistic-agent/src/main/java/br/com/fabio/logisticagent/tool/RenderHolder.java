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
    private boolean renderAllowed = true;

    /**
     * O usuário pediu gráfico/tabela nesta requisição? Quem decide é o ChatService, a partir da
     * mensagem — a tool não vê a pergunta. Com false, renderChart/renderTable recusam: o modelo
     * desenhava um gráfico por conta própria em pergunta analítica ("taxa de falha por estado") e
     * ainda repetia os dados em markdown, enchendo a tela com o que ninguém pediu.
     */
    public void setRenderAllowed(boolean renderAllowed) {
        this.renderAllowed = renderAllowed;
    }

    public boolean isRenderAllowed() {
        return renderAllowed;
    }

    /**
     * Recusa que não é culpa dos argumentos (render não pedido, ou segunda visualização na mesma
     * resposta). Conta para o teto de recusas — senão o modelo determinístico repete a chamada em
     * loop — mas não vira aviso de falha na tela: não há nada a desmentir.
     */
    public int registerIgnored() {
        return ++this.rejections;
    }

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
