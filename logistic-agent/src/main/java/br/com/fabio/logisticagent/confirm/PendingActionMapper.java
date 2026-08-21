package br.com.fabio.logisticagent.confirm;

import br.com.fabio.logisticagent.dto.PendingActionDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Traduz a pendência para o que o usuário lê antes de confirmar.
 *
 * <p>O texto é montado em código, e não pedido ao modelo, pelo mesmo motivo de o payload
 * confirmado ser o payload registrado: se a frase da confirmação viesse da LLM, o usuário
 * aprovaria a descrição dela e não a chamada que vai rodar — e as duas divergem justamente nos
 * casos em que a confirmação importa.
 */
@Component
public class PendingActionMapper {

    private static final Logger log = LoggerFactory.getLogger(PendingActionMapper.class);

    /** Uma frase por tool de escrita. Tool nova sem entrada aqui cai no fallback com o nome cru. */
    private static final Map<String, String> ACTION_PT = Map.ofEntries(
            Map.entry("createDriver", "Cadastrar um novo motorista"),
            Map.entry("linkDriverVehicle", "Vincular um veículo a um motorista"),
            Map.entry("createVehicle", "Cadastrar um novo veículo"),
            Map.entry("createOrder", "Cadastrar um novo pedido"),
            Map.entry("updateOrderStatus", "Alterar o status de um pedido"),
            Map.entry("createRoute", "Criar uma nova rota"),
            Map.entry("updateRouteStatus", "Alterar o status de uma rota"),
            Map.entry("assignOrderToRoute", "Vincular um pedido a uma rota"),
            Map.entry("deleteDriver", "EXCLUIR um motorista (irreversível)"),
            Map.entry("deleteVehicle", "EXCLUIR um veículo da frota (irreversível)"));

    /** Rótulo PT-BR dos argumentos, para a tela não mostrar "birthday" e "zipCode". */
    private static final Map<String, String> FIELD_PT = Map.ofEntries(
            Map.entry("id", "Id"),
            Map.entry("name", "Nome"),
            Map.entry("email", "E-mail"),
            Map.entry("birthday", "Nascimento"),
            Map.entry("city", "Cidade"),
            Map.entry("state", "Estado"),
            Map.entry("street", "Rua"),
            Map.entry("number", "Número"),
            Map.entry("zipCode", "CEP"),
            Map.entry("status", "Status"),
            Map.entry("capacityKg", "Capacidade (kg)"),
            Map.entry("plate", "Placa"),
            Map.entry("model", "Modelo"),
            Map.entry("driverId", "Id do motorista"),
            Map.entry("vehicleId", "Id do veículo"),
            Map.entry("orderId", "Id do pedido"),
            Map.entry("routeId", "Id da rota"));

    private final JsonMapper jsonMapper;

    public PendingActionMapper(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    public PendingActionDTO toDto(PendingAction action) {
        return new PendingActionDTO(action.id(), action.toolName(), summary(action.toolName()),
                displayed(action), destructive(action.toolName()));
    }

    /**
     * O que o card mostra. Numa exclusão são os campos do registro alvo — o argumento é só um
     * UUID, e confirmar um UUID não é conferir nada, ainda mais com nome de motorista não sendo
     * único. Nas demais ações os próprios argumentos já são o que o usuário informou.
     */
    private Map<String, String> displayed(PendingAction action) {
        if (action.details().isEmpty()) {
            return arguments(action.argsJson());
        }
        Map<String, String> displayed = new LinkedHashMap<>(action.details());
        displayed.putAll(arguments(action.argsJson()));
        return displayed;
    }

    /**
     * Exclusão é irreversível e não tem "desfazer" na plataforma — o card precisa parecer diferente
     * de um cadastro. Deriva do nome da tool: {@code delete*} nasce marcada, sem lista para manter.
     */
    private boolean destructive(String toolName) {
        return simpleName(toolName).startsWith("delete");
    }

    private String simpleName(String toolName) {
        return toolName.substring(toolName.lastIndexOf('_') + 1);
    }

    /** Rótulo PT-BR de um campo, ou o nome cru quando não há tradução. Usado também na crítica
     * de campo obrigatório faltando, para o modelo perguntar ao usuário com o mesmo nome que a
     * tela mostra depois. */
    public String label(String field) {
        return FIELD_PT.getOrDefault(field, field);
    }

    private String summary(String toolName) {
        String simpleName = simpleName(toolName);
        return ACTION_PT.getOrDefault(simpleName, "Executar a operação " + simpleName);
    }

    /**
     * Argumentos do modelo, achatados em texto. Falha de parse não derruba a confirmação: a ação
     * segue válida (o que será executado é o JSON cru, não este mapa) e a tela mostra o payload
     * como veio.
     */
    private Map<String, String> arguments(String argsJson) {
        Map<String, String> arguments = new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = jsonMapper.readValue(argsJson, new TypeReference<Map<String, Object>>() {
            });
            parsed.forEach((key, value) -> arguments.put(label(key), String.valueOf(value)));
        } catch (JacksonException | IllegalArgumentException e) {
            log.warn("Argumentos da ação {} não são um JSON de objeto: {}", argsJson, e.getMessage());
            arguments.put("Argumentos", argsJson);
        }
        return arguments;
    }
}
