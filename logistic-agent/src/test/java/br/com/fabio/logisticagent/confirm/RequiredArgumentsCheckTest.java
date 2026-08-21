package br.com.fabio.logisticagent.confirm;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

class RequiredArgumentsCheckTest {

    private static final String SCHEMA = """
            {"type":"object",
             "properties":{"name":{"type":"string"},"email":{"type":"string"},
                           "capacityKg":{"type":"integer"}},
             "required":["name","email"]}
            """;

    private final JsonMapper jsonMapper = JsonMapper.builder().build();
    private final RequiredArgumentsCheck check =
            new RequiredArgumentsCheck(jsonMapper, new PendingActionMapper(jsonMapper));

    private ToolDefinition tool(String schema) {
        return DefaultToolDefinition.builder()
                .name("createDriver").description("createDriver").inputSchema(schema).build();
    }

    @Test
    void completeCallHasNothingMissing() {
        assertThat(check.missingFrom(tool(SCHEMA), "{\"name\":\"João\",\"email\":\"j@x.com\"}")).isEmpty();
    }

    @Test
    void missingFieldIsReportedWithItsPortugueseLabel() {
        assertThat(check.missingFrom(tool(SCHEMA), "{\"name\":\"João\"}")).containsExactly("E-mail");
    }

    @Test
    void nullAndBlankCountAsMissing() {
        assertThat(check.missingFrom(tool(SCHEMA), "{\"name\":null,\"email\":\"  \"}"))
                .containsExactly("Nome", "E-mail");
    }

    @Test
    void placeholderValuesCountAsMissing() {
        assertThat(check.missingFrom(tool(SCHEMA), "{\"name\":\"N/A\",\"email\":\"não informado\"}"))
                .containsExactly("Nome", "E-mail");
    }

    /** Campo opcional ausente não é cobrado: quem manda é o "required" do schema. */
    @Test
    void optionalFieldIsNotRequired() {
        assertThat(check.missingFrom(tool(SCHEMA), "{\"name\":\"João\",\"email\":\"j@x.com\"}")).isEmpty();
    }

    /** Número zero é um valor: só string vazia e placeholder contam como ausência. */
    @Test
    void zeroIsAValue() {
        String schema = """
                {"type":"object","properties":{"capacityKg":{"type":"integer"}},"required":["capacityKg"]}
                """;
        assertThat(check.missingFrom(tool(schema), "{\"capacityKg\":0}")).isEmpty();
    }

    /**
     * Sem o que julgar, a chamada passa — a alternativa (recusar) transformaria uma chamada correta
     * num loop de crítica só porque o schema não declara obrigatórios.
     */
    @Test
    void schemaWithoutRequiredNeverBlocks() {
        assertThat(check.missingFrom(tool("{\"type\":\"object\"}"), "{}")).isEmpty();
        assertThat(check.missingFrom(tool(SCHEMA), "não é json")).isEmpty();
        assertThat(check.missingFrom(tool(SCHEMA), "[1,2]")).isEmpty();
    }
}
