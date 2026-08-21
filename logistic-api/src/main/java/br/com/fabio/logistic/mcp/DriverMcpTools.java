package br.com.fabio.logistic.mcp;

import br.com.fabio.logistic.dto.DeletionSummary;
import br.com.fabio.logistic.dto.DriverRequest;
import br.com.fabio.logistic.dto.DriverResponse;
import br.com.fabio.logistic.service.DriverService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.UUID;

/** Tools MCP de motorista — só escrita; leitura é via executeQuery. */
@Component
public class DriverMcpTools {

    private final DriverService driverService;

    public DriverMcpTools(DriverService driverService) {
        this.driverService = driverService;
    }

    @McpTool(description = """
            Cadastra um novo motorista. E-mail deve ser único — se já existir, a operação é recusada.
            Obrigatórios: name, email, birthday, city, state. Se o usuário não informou todos,
            PERGUNTE os que faltam antes de chamar — não invente valor nem use "N/A".
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

    @McpTool(description = """
            Vincula um veículo a um motorista (relação N:N via driver_vehicle). Falha se o vínculo já
            existir. Obrigatórios: driverId, vehicleId — os ids vêm de uma consulta executeQuery
            anterior; se não souber, consulte antes em vez de inventar um UUID.
            """)
    public String linkDriverVehicle(
            @McpToolParam(description = "Id (UUID) do motorista") UUID driverId,
            @McpToolParam(description = "Id (UUID) do veículo") UUID vehicleId) {
        driverService.linkVehicle(driverId, vehicleId);
        return "Motorista " + driverId + " vinculado ao veículo " + vehicleId + " com sucesso.";
    }

    @McpTool(description = """
            Exclui um motorista da plataforma. Operação irreversível.
            Obrigatório: id (UUID) — vem de uma consulta executeQuery anterior pelo nome ou e-mail;
            NUNCA invente um UUID e nunca chame esta tool sem ter buscado o id antes.
            Um motorista com rotas não pode ser excluído: a operação é recusada e a mensagem diz
            quantas rotas existem. Os vínculos dele com veículos são desfeitos junto (os veículos
            continuam na frota).
            Exemplo: id="3fa85f64-5717-4562-b3fc-2c963f66afa6".
            """)
    public String deleteDriver(
            @McpToolParam(description = "Id (UUID) do motorista a ser excluído") UUID id) {
        DeletionSummary summary = driverService.delete(id);
        return "Motorista " + summary.name() + " (" + summary.id() + ") excluído."
                + (summary.removedLinks() > 0
                        ? " " + summary.removedLinks() + " vínculo(s) com veículos foram desfeitos."
                        : "");
    }
}
