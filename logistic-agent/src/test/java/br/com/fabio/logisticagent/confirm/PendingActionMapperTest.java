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
        return new PendingAction("acao-1", "sessao-1", toolName, argsJson, null, Instant.now(), details);
    }

    /**
     * O bean de JSON do Boot 4 é o {@code JsonMapper} do Jackson 3 ({@code tools.jackson}); o
     * {@code com.fasterxml.jackson.databind.ObjectMapper} está no classpath por transitividade,
     * mas não existe como bean — pedir por ele derrubava a aplicação no startup, e nenhum teste
     * unitário via, porque nenhum subia contexto.
     */
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

    /** Nome prefixado pelo cliente MCP continua achando a frase. */
    @Test
    void handlesPrefixedToolNames() {
        assertThat(mapper.toDto(action("logistic_createVehicle", "{}")).summary())
                .isEqualTo("Cadastrar um novo veículo");
    }

    /** Tool sem entrada no mapa não pode virar card em branco. */
    @Test
    void unknownToolFallsBackToItsName() {
        assertThat(mapper.toDto(action("cancelRoute", "{}")).summary())
                .isEqualTo("Executar a operação cancelRoute");
    }

    /** Campo sem rótulo aparece com o nome cru — melhor que sumir da tela de confirmação. */
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

    /** Exclusão é irreversível: o card precisa nascer marcado para o frontend pintar diferente. */
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

    /** Numa exclusão o card mostra o registro alvo; o UUID sozinho não deixa conferir nada. */
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

    /**
     * A ordem dos campos é a declarada no lookup — o card mostra "Nome, E-mail, Cidade, Estado",
     * não o que o mapa resolver. Com dois campos o teste passava por sorte: {@code Map.copyOf}
     * tem ordem de iteração não especificada, e ela muda de execução para execução.
     */
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
