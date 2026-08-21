package br.com.fabio.logistic.mcp;

import br.com.fabio.logistic.dto.VehicleRequest;
import br.com.fabio.logistic.dto.VehicleResponse;
import br.com.fabio.logistic.service.VehicleService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/** Tools MCP de veículo — só escrita; leitura é via executeQuery. */
@Component
public class VehicleMcpTools {

    private final VehicleService vehicleService;

    public VehicleMcpTools(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @McpTool(description = """
            Cadastra um novo veículo na frota. Exemplo: name="Van Mercedes Sprinter", capacityKg=1200.
            """)
    public VehicleResponse createVehicle(
            @McpToolParam(description = "Nome ou modelo do veículo") String name,
            @McpToolParam(description = "Capacidade de carga do veículo, em quilogramas") Integer capacityKg) {
        return vehicleService.create(new VehicleRequest(name, capacityKg));
    }
}
