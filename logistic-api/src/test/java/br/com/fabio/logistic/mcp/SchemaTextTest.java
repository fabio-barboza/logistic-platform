package br.com.fabio.logistic.mcp;

import br.com.fabio.logistic.domain.enums.OrderStatus;
import br.com.fabio.logistic.domain.enums.RouteStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaTextTest {

    @Test
    void everyRouteStatusIsDocumentedWithItsDescription() {
        for (RouteStatus status : RouteStatus.values()) {
            assertThat(SchemaText.ENUMS)
                    .as("status de rota %s ausente no schema", status)
                    .contains(status.name())
                    .contains(status.getDescription());
        }
    }

    @Test
    void everyOrderStatusIsDocumentedWithItsDescription() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(SchemaText.ENUMS)
                    .as("status de pedido %s ausente no schema", status)
                    .contains(status.name())
                    .contains(status.getDescription());
        }
    }

    @Test
    void finalStatusesAreMarkedAsSuch() {
        for (RouteStatus status : RouteStatus.values()) {
            assertThat(lineOf(status.name())).as("linha de %s", status)
                    .satisfies(line -> assertThat(line.contains("(finalizador)")).isEqualTo(status.isFinal()));
        }
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(lineOf(status.name())).as("linha de %s", status)
                    .satisfies(line -> assertThat(line.contains("(finalizador)")).isEqualTo(status.isFinal()));
        }
    }

    @Test
    void fullSchemaCarriesTablesEnumsAndRules() {
        assertThat(SchemaText.FULL)
                .contains("capacity_kg")
                .contains("zip_code")
                .contains(SchemaText.ENUMS)
                .contains(SchemaText.QUERY_RULES);
    }

    private String lineOf(String statusName) {
        String lines = SchemaText.ENUMS.lines()
                .filter(line -> line.contains(statusName + " "))
                .reduce("", (a, b) -> a + b);
        assertThat(lines).as("status %s não aparece no schema", statusName).isNotEmpty();
        return lines;
    }
}
