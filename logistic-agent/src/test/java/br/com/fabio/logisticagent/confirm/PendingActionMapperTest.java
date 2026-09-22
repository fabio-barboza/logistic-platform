package br.com.fabio.logisticagent.confirm;

import br.com.fabio.logisticagent.dto.PendingActionDTO;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PendingActionMapperTest {

    private final PendingActionMapper mapper = new PendingActionMapper(JsonMapper.builder().build());

    private PendingAction action(String toolName, String argsJson) {
        return action(toolName, argsJson, Map.of());
    }

    private PendingAction action(String toolName, String argsJson, Map<String, String> details) {
        return new PendingAction("acao-1", "sessao-1", toolName, argsJson, Instant.now(), details);
    }

    @Test
    void wiresWithTheJsonMapperBeanFromAutoConfiguration() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(PendingActionMapper.class)
                .run(context -> assertThat(context).hasSingleBean(PendingActionMapper.class));
    }

    @Test
    void translatesToolNameAndFields() {
        PendingActionDTO dto = mapper.toDto(action("createDriver",
                "{\"name\":\"João Silva\",\"birthday\":\"1990-05-10\",\"state\":\"SP\"}"));

        assertThat(dto.summary()).isEqualTo("Cadastrar um novo motorista");
        assertThat(dto.arguments())
                .containsEntry("Nome", "João Silva")
                .containsEntry("Nascimento", "1990-05-10")
                .containsEntry("Estado", "SP");
    }

    @Test
    void handlesPrefixedToolNames() {
        assertThat(mapper.toDto(action("logistic_createVehicle", "{}")).summary())
                .isEqualTo("Cadastrar um novo veículo");
    }

    @Test
    void unknownToolFallsBackToItsName() {
        assertThat(mapper.toDto(action("cancelRoute", "{}")).summary())
                .isEqualTo("Executar a operação cancelRoute");
    }

    @Test
    void unknownFieldKeepsItsRawName() {
        assertThat(mapper.toDto(action("createOrder", "{\"weird\":\"x\"}")).arguments())
                .containsEntry("weird", "x");
    }

    @Test
    void invalidJsonIsShownAsIs() {
        assertThat(mapper.toDto(action("createDriver", "not json")).arguments())
                .containsEntry("Argumentos", "not json");
    }

    @Test
    void deleteToolsAreFlaggedAsDestructive() {
        assertThat(mapper.toDto(action("deleteDriver", "{\"id\":\"abc\"}")).destructive()).isTrue();
        assertThat(mapper.toDto(action("logistic_deleteVehicle", "{}")).destructive()).isTrue();
        assertThat(mapper.toDto(action("createDriver", "{}")).destructive()).isFalse();
    }

    @Test
    void deleteToolsHaveTheirOwnSummary() {
        assertThat(mapper.toDto(action("deleteDriver", "{}")).summary()).contains("EXCLUIR um motorista");
        assertThat(mapper.toDto(action("deleteVehicle", "{}")).summary()).contains("EXCLUIR um veículo");
    }

    @Test
    void deletionShowsTheTargetRecordBeforeTheId() {
        PendingActionDTO dto = mapper.toDto(action("deleteDriver",
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

        PendingActionDTO dto = mapper.toDto(action("deleteDriver", "{}", details));

        assertThat(dto.arguments().keySet())
                .containsExactly("Nome", "E-mail", "Cidade", "Estado");
    }
}
