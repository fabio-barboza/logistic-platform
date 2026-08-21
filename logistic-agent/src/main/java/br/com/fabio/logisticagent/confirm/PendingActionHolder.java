package br.com.fabio.logisticagent.confirm;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

/**
 * A ação pendente criada <b>nesta requisição</b>, se houver.
 *
 * <p>Mesmo papel do RenderHolder: a tool não tem como devolver nada ao ChatService a não ser
 * pelo holder — o retorno dela vai para o modelo, não para o controller. E mesmo motivo para o
 * escopo ser de requisição: com escopo maior, o "confirme esta ação" de um usuário apareceria na
 * resposta de outro.
 *
 * <p>Uma pendência por requisição. Confirmar em bloco ("cadastre 3 motoristas") exigiria uma
 * fila na tela e um id por item; enquanto isso não existe, a segunda escrita do mesmo turno é
 * recusada e o modelo é mandado pedir uma de cada vez.
 */
@Component
@RequestScope
public class PendingActionHolder {

    private PendingAction action;
    private int rejections;
    private String sessionId;

    /**
     * Conversa desta requisição, gravada pelo ChatService — {@code AuthenticatedUser.conversationId},
     * não o sessionId cru: isolar por usuário além de por aba é o que impede a pendência de um
     * usuário ser resgatável por outro que force o mesmo sessionId. A tool não recebe esse valor
     * (ela só vê os argumentos que o modelo escreveu), e sem ele a pendência não teria a quem
     * pertencer — o resgate na confirmação exige o par (id, sessionId).
     */
    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String sessionId() {
        return sessionId;
    }

    public void set(PendingAction action) {
        this.action = action;
    }

    public PendingAction get() {
        return action;
    }

    /** Chamada de escrita recusada porque já havia outra pendente nesta requisição. */
    public int registerRejection() {
        return ++this.rejections;
    }

    public int rejections() {
        return rejections;
    }
}
