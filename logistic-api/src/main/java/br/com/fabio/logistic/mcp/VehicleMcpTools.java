package br.com.fabio.logistic.mcp;

import br.com.fabio.logistic.dto.VehicleFilter;
import br.com.fabio.logistic.dto.VehicleRequest;
import br.com.fabio.logistic.dto.VehicleResponse;
import br.com.fabio.logistic.service.VehicleService;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** Tools MCP de veículo — delegam ao VehicleService, sem lógica própria. */
@Component
public class VehicleMcpTools {

    private final VehicleService vehicleService;

    public VehicleMcpTools(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @McpTool(description = """
            Busca veículos da frota, com filtros opcionais combinados em AND. Use para listar veículos
            por faixa de capacidade ou vinculados a um motorista. Exemplo: capacidade entre 500 e 1000 ->
            capacityMin=500, capacityMax=1000.
            """)
    public List<VehicleResponse> searchVehicles(
            @McpToolParam(required = false, description = "Parte do nome/modelo do veículo. Exemplo: \"van\"") String name,
            @McpToolParam(required = false, description = "Capacidade mínima de carga") Integer capacityMin,
            @McpToolParam(required = false, description = "Capacidade máxima de carga") Integer capacityMax,
            @McpToolParam(required = false, description = "Id do motorista ao qual o veículo está vinculado") UUID driverId,
            @McpToolParam(required = false, description = "Quantidade máxima de resultados. Default 25, máximo 100") Integer limit) {
        VehicleFilter filter = new VehicleFilter(name, capacityMin, capacityMax, driverId);
        Pageable pageable = McpPageSupport.of(limit);
        return vehicleService.search(filter, pageable).getContent();
    }

    @McpTool(description = "Busca um veículo pelo id. Devolve os dados completos do veículo.")
    public VehicleResponse getVehicle(
            @McpToolParam(description = "Id (UUID) do veículo. Exemplo: \"3fa85f64-5717-4562-b3fc-2c963f66afa6\"") UUID id) {
        return vehicleService.findById(id);
    }

    @McpTool(description = """
            Cadastra um novo veículo na frota. Exemplo: name="Van Mercedes Sprinter", capacity=1200.
            """)
    public VehicleResponse createVehicle(
            @McpToolParam(description = "Nome ou modelo do veículo") String name,
            @McpToolParam(description = "Capacidade de carga do veículo") Integer capacity) {
        return vehicleService.create(new VehicleRequest(name, capacity));
    }
}
