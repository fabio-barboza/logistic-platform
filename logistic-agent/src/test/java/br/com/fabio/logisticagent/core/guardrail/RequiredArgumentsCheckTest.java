package br.com.fabio.logisticagent.core.guardrail;

import org.junit.jupiter.api.Test;
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
    private final RequiredArgumentsCheck check = new RequiredArgumentsCheck(jsonMapper);

    @Test
    void completeCallHasNothingMissing() {
        assertThat(check.missingFrom("createDriver", SCHEMA, "{\"name\":\"João\",\"email\":\"j@x.com\"}")).isEmpty();
    }

    @Test
    void missingFieldIsReportedWithItsPortugueseLabel() {
        assertThat(check.missingFrom("createDriver", SCHEMA, "{\"name\":\"João\"}")).containsExactly("E-mail");
    }

    @Test
    void nullAndBlankCountAsMissing() {
        assertThat(check.missingFrom("createDriver", SCHEMA, "{\"name\":null,\"email\":\"  \"}"))
                .containsExactly("Nome", "E-mail");
    }

    @Test
    void placeholderValuesCountAsMissing() {
        assertThat(check.missingFrom("createDriver", SCHEMA, "{\"name\":\"N/A\",\"email\":\"não informado\"}"))
                .containsExactly("Nome", "E-mail");
    }

    @Test
    void optionalFieldIsNotRequired() {
        assertThat(check.missingFrom("createDriver", SCHEMA, "{\"name\":\"João\",\"email\":\"j@x.com\"}")).isEmpty();
    }

    @Test
    void zeroIsAValue() {
        String schema = """
                {"type":"object","properties":{"capacityKg":{"type":"integer"}},"required":["capacityKg"]}
                """;
        assertThat(check.missingFrom("createDriver", schema, "{\"capacityKg\":0}")).isEmpty();
    }

    @Test
    void schemaWithoutRequiredNeverBlocks() {
        assertThat(check.missingFrom("createDriver", "{\"type\":\"object\"}", "{}")).isEmpty();
        assertThat(check.missingFrom("createDriver", SCHEMA, "não é json")).isEmpty();
        assertThat(check.missingFrom("createDriver", SCHEMA, "[1,2]")).isEmpty();
    }
}
