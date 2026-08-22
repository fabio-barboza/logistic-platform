package br.com.fabio.logistic.mcp;

import br.com.fabio.logistic.dto.DeletionSummary;
import br.com.fabio.logistic.dto.VehicleRequest;
import br.com.fabio.logistic.dto.VehicleResponse;
import br.com.fabio.logistic.service.VehicleService;
import io.modelcontextprotocol.common.McpTransportContext;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

import java.util.UUID;

/** Tools MCP de veículo — só escrita; leitura é via executeQuery. */
@Component
public class VehicleMcpTools {

    private final VehicleService vehicleService;
    private final McpAuthorization mcpAuthorization;

    public VehicleMcpTools(VehicleService vehicleService, McpAuthorization mcpAuthorization) {
        this.vehicleService = vehicleService;
        this.mcpAuthorization = mcpAuthorization;
    }

    @McpTool(description = """
            Cadastra um novo veículo na frota. Obrigatórios: name, capacityKg. Se o usuário não
            informou a capacidade, PERGUNTE antes de chamar — não invente valor.
            Exemplo: name="Van Mercedes Sprinter", capacityKg=1200.
            """)
    public VehicleResponse createVehicle(
            McpTransportContext ctx,
            @McpToolParam(description = "Nome ou modelo do veículo") String name,
            @McpToolParam(description = "Capacidade de carga do veículo, em quilogramas") Integer capacityKg) {
        mcpAuthorization.require(ctx, "write");
        return vehicleService.create(new VehicleRequest(name, capacityKg));
    }

    @McpTool(description = """
            Exclui um veículo da frota. Operação irreversível.
            Obrigatório: id (UUID) — vem de uma consulta executeQuery anterior pelo nome do veículo;
            NUNCA invente um UUID e nunca chame esta tool sem ter buscado o id antes.
            Os vínculos do veículo com motoristas são desfeitos junto; nenhum motorista é excluído.
            Exemplo: id="3fa85f64-5717-4562-b3fc-2c963f66afa6".
            """)
    public String deleteVehicle(
            McpTransportContext ctx,
            @McpToolParam(description = "Id (UUID) do veículo a ser excluído") UUID id) {
        mcpAuthorization.require(ctx, "write");
        DeletionSummary summary = vehicleService.delete(id);
        return "Veículo " + summary.name() + " (" + summary.id() + ") excluído da frota."
                + (summary.removedLinks() > 0
                        ? " " + summary.removedLinks() + " vínculo(s) com motoristas foram desfeitos."
                        : "");
    }
}
