package br.com.fabio.logisticagent.confirm;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uma chamada de tool de escrita interceptada antes de executar, à espera do "confirmar" do usuário.
 *
 * <p>Guarda o JSON de argumentos <b>exatamente como o modelo mandou</b>: a confirmação reexecuta
 * esse mesmo payload, sem passar de novo pela LLM. Pedir ao modelo para refazer a chamada depois
 * do "sim" é o caminho fácil e errado — ele reescreve valores (nome, capacidade, id) e o usuário
 * confirma uma coisa enquanto outra é gravada.
 *
 * <p>Guarda o <b>nome</b> da tool, não a tool em si: um {@code ToolCallback} fecha sobre o
 * {@code McpSyncClient} e não vai para uma linha de banco. {@code ConfirmationService} resolve o
 * callback pelo nome na hora de confirmar.
 *
 * <p>É a própria entidade JPA, como {@code Driver} e {@code Order} na logistic-api — a alternativa
 * era um record mais uma entidade espelho e a tradução entre as duas, sem nada a ganhar.
 */
@Entity
@Table(name = "pending_action")
public class PendingAction {

    @Id
    private String id;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "tool_name", nullable = false)
    private String toolName;

    @Column(name = "args_json", nullable = false)
    private String argsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /**
     * Serializado como texto pelo conversor, nunca {@code jsonb}: o card de exclusão mostra os
     * campos na ordem que {@link DeletionTargetLookup} declarou, e o {@code jsonb} do Postgres não
     * preserva ordem de chave.
     */
    @Column(name = "details_json", nullable = false)
    @Convert(converter = DetailsJsonConverter.class)
    private Map<String, String> details;

    protected PendingAction() {
    }

    public PendingAction(String id, String sessionId, String toolName, String argsJson,
            Instant createdAt, Map<String, String> details) {
        this.id = id;
        this.sessionId = sessionId;
        this.toolName = toolName;
        this.argsJson = argsJson;
        this.createdAt = createdAt;
        // LinkedHashMap, e não Map.copyOf: mapa imutável do JDK tem ordem de iteração NÃO
        // especificada, e o card saía com "Cidade, Estado, E-mail, Nome".
        this.details = details == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(details));
    }

    public String id() {
        return id;
    }

    public String sessionId() {
        return sessionId;
    }

    public String toolName() {
        return toolName;
    }

    public String argsJson() {
        return argsJson;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Map<String, String> details() {
        return details;
    }

    /** Mesma tool, mesmos argumentos: o modelo repetiu a chamada em vez de esperar. */
    public boolean matches(String otherTool, String otherArgs) {
        return toolName.equals(otherTool) && argsJson.equals(otherArgs == null ? "" : otherArgs);
    }
}
