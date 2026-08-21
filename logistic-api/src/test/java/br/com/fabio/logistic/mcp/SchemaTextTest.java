package br.com.fabio.logistic.mcp;

import br.com.fabio.logistic.domain.enums.OrderStatus;
import br.com.fabio.logistic.domain.enums.RouteStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * O schema é texto em constante porque descrição de {@code @McpTool} é anotação e anotação exige
 * constante de compilação — não dá para gerar do enum em runtime. O que sobra é este teste: um
 * status novo no domínio quebra aqui, em vez de virar uma tradução faltando na tela meses depois.
 */
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

    /** O schema inteiro precisa chegar ao modelo pela descrição do executeQuery, não só pela tool. */
    @Test
    void fullSchemaCarriesTablesEnumsAndRules() {
        assertThat(SchemaText.FULL)
                .contains("capacity_kg")
                .contains("zip_code")
                .contains(SchemaText.ENUMS)
                .contains(SchemaText.QUERY_RULES);
    }

    /**
     * Todas as linhas do status, não a primeira: CANCELED existe nos dois enums, e pegar só a
     * primeira ocorrência checaria route_status achando que estava checando order_status.
     */
    private String lineOf(String statusName) {
        String lines = SchemaText.ENUMS.lines()
                .filter(line -> line.contains(statusName + " "))
                .reduce("", (a, b) -> a + b);
        assertThat(lines).as("status %s não aparece no schema", statusName).isNotEmpty();
        return lines;
    }
}
