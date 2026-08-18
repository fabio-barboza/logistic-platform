package br.com.fabio.logistic.mcp;

import br.com.fabio.logistic.dto.DriverFilter;
import br.com.fabio.logistic.dto.DriverRequest;
import br.com.fabio.logistic.dto.DriverResponse;
import br.com.fabio.logistic.service.DriverService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Tools MCP de motorista — delegam ao DriverService, sem lógica própria. */
@Component
public class DriverMcpTools {

    private final DriverService driverService;

    public DriverMcpTools(DriverService driverService) {
        this.driverService = driverService;
    }

    @McpTool(description = """
            Busca motoristas cadastrados, com filtros opcionais combinados em AND. Use para listar
            motoristas por cidade, estado, nome (busca parcial) ou vinculados a um veículo específico.
            Exemplo: buscar motoristas de "SP" -> state="SP". Exemplo: motoristas com "silva" no nome ->
            name="silva".
            """)
    public List<DriverResponse> searchDrivers(
            @McpToolParam(required = false, description = "Parte do nome do motorista, busca case-insensitive. Exemplo: \"maria\"") String name,
            @McpToolParam(required = false, description = "Parte do e-mail do motorista. Exemplo: \"gmail\"") String email,
            @McpToolParam(required = false, description = "Cidade exata do motorista. Exemplo: \"São Paulo\"") String city,
            @McpToolParam(required = false, description = "Sigla do estado (UF), 2 letras. Exemplo: \"SP\"") String state,
            @McpToolParam(required = false, description = "Data de nascimento mínima (ISO yyyy-MM-dd). Exemplo: \"1980-01-01\"") LocalDate birthdayFrom,
            @McpToolParam(required = false, description = "Data de nascimento máxima (ISO yyyy-MM-dd). Exemplo: \"2000-12-31\"") LocalDate birthdayTo,
            @McpToolParam(required = false, description = "Id do veículo ao qual o motorista está vinculado") UUID vehicleId,
            @McpToolParam(required = false, description = "Quantidade máxima de resultados. Default 100, máximo 500") Integer limit) {
        DriverFilter filter = new DriverFilter(name, email, city, state, birthdayFrom, birthdayTo, vehicleId);
        Pageable pageable = McpPageSupport.of(limit);
        return driverService.search(filter, pageable).getContent();
    }

    @McpTool(description = "Busca um motorista pelo id. Devolve os dados completos do motorista.")
    public DriverResponse getDriver(
            @McpToolParam(description = "Id (UUID) do motorista. Exemplo: \"3fa85f64-5717-4562-b3fc-2c963f66afa6\"") UUID id) {
        return driverService.findById(id);
    }

    @McpTool(description = """
            Cadastra um novo motorista. E-mail deve ser único — se já existir, a operação é recusada.
            Exemplo: name="João Silva", email="joao.silva@email.com", birthday="1990-05-10",
            city="Campinas", state="SP".
            """)
    public DriverResponse createDriver(
            @McpToolParam(description = "Nome completo do motorista") String name,
            @McpToolParam(description = "E-mail único do motorista") String email,
            @McpToolParam(description = "Data de nascimento (ISO yyyy-MM-dd)") LocalDate birthday,
            @McpToolParam(description = "Cidade de residência") String city,
            @McpToolParam(description = "Sigla do estado (UF), 2 letras") String state) {
        return driverService.create(new DriverRequest(name, email, birthday, city, state));
    }

    @McpTool(description = "Vincula um veículo a um motorista (relação N:N via driver_vehicle). Falha se o vínculo já existir.")
    public String linkDriverVehicle(
            @McpToolParam(description = "Id (UUID) do motorista") UUID driverId,
            @McpToolParam(description = "Id (UUID) do veículo") UUID vehicleId) {
        driverService.linkVehicle(driverId, vehicleId);
        return "Motorista " + driverId + " vinculado ao veículo " + vehicleId + " com sucesso.";
    }
}
