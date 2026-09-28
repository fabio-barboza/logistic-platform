package br.com.fabio.logisticagent.entrypoint.mapper;

import br.com.fabio.logisticagent.core.domain.chat.PendingAction;
import br.com.fabio.logisticagent.entrypoint.response.PendingActionResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseMapperTest {

    private PendingAction action(String toolName, String argsJson) {
        return action(toolName, argsJson, Map.of());
    }

    private PendingAction action(String toolName, String argsJson, Map<String, String> details) {
        return new PendingAction("acao-1", "sessao-1", toolName, argsJson, Instant.now(), details);
    }

    @Test
    void translatesToolNameAndFields() {
        PendingActionResponse dto = ResponseMapper.toResponse(action("createDriver",
                "{\"name\":\"João Silva\",\"birthday\":\"1990-05-10\",\"state\":\"SP\"}"));

        assertThat(dto.summary()).isEqualTo("Cadastrar um novo motorista");
        assertThat(dto.arguments())
                .containsEntry("Nome", "João Silva")
                .containsEntry("Nascimento", "1990-05-10")
                .containsEntry("Estado", "SP");
    }

    @Test
    void handlesPrefixedToolNames() {
        assertThat(ResponseMapper.toResponse(action("logistic_createVehicle", "{}")).summary())
                .isEqualTo("Cadastrar um novo veículo");
    }

    @Test
    void unknownToolFallsBackToItsName() {
        assertThat(ResponseMapper.toResponse(action("cancelRoute", "{}")).summary())
                .isEqualTo("Executar a operação cancelRoute");
    }

    @Test
    void unknownFieldKeepsItsRawName() {
        assertThat(ResponseMapper.toResponse(action("createOrder", "{\"weird\":\"x\"}")).arguments())
                .containsEntry("weird", "x");
    }

    @Test
    void invalidJsonIsShownAsIs() {
        assertThat(ResponseMapper.toResponse(action("createDriver", "not json")).arguments())
                .containsEntry("Argumentos", "not json");
    }

    @Test
    void deleteToolsAreFlaggedAsDestructive() {
        assertThat(ResponseMapper.toResponse(action("deleteDriver", "{\"id\":\"abc\"}")).destructive()).isTrue();
        assertThat(ResponseMapper.toResponse(action("logistic_deleteVehicle", "{}")).destructive()).isTrue();
        assertThat(ResponseMapper.toResponse(action("createDriver", "{}")).destructive()).isFalse();
    }

    @Test
    void deleteToolsHaveTheirOwnSummary() {
        assertThat(ResponseMapper.toResponse(action("deleteDriver", "{}")).summary()).contains("EXCLUIR um motorista");
        assertThat(ResponseMapper.toResponse(action("deleteVehicle", "{}")).summary()).contains("EXCLUIR um veículo");
    }

    @Test
    void deletionShowsTheTargetRecordBeforeTheId() {
        PendingActionResponse dto = ResponseMapper.toResponse(action("deleteDriver",
                "{\"id\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"}",
                Map.of("Nome", "João Ribeiro", "E-mail", "joao@x.com")));

        assertThat(dto.arguments())
                .containsEntry("Nome", "João Ribeiro")
                .containsEntry("E-mail", "joao@x.com")
                .containsEntry("Id", "3fa85f64-5717-4562-b3fc-2c963f66afa6");
        assertThat(dto.arguments().keySet().iterator().next()).isIn("Nome", "E-mail");
    }

    @Test
    void deletionDetailsKeepTheDeclaredOrder() {
        Map<String, String> details = new LinkedHashMap<>();
        details.put("Nome", "Ana Prado");
        details.put("E-mail", "ana.prado@teste.com");
        details.put("Cidade", "Florianópolis");
        details.put("Estado", "SC");

        PendingActionResponse dto = ResponseMapper.toResponse(action("deleteDriver", "{}", details));

        assertThat(dto.arguments().keySet())
                .containsExactly("Nome", "E-mail", "Cidade", "Estado");
    }
}
