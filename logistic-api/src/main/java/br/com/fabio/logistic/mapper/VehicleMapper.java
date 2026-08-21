package br.com.fabio.logistic.mapper;

import br.com.fabio.logistic.domain.Vehicle;
import br.com.fabio.logistic.dto.VehicleRequest;
import br.com.fabio.logistic.dto.VehicleResponse;
import org.springframework.stereotype.Component;

@Component
public class VehicleMapper {

    public Vehicle toEntity(VehicleRequest request) {
        Vehicle vehicle = new Vehicle();
        vehicle.setName(request.name());
        vehicle.setCapacityKg(request.capacityKg());
        return vehicle;
    }

    public void updateEntity(Vehicle vehicle, VehicleRequest request) {
        vehicle.setName(request.name());
        vehicle.setCapacityKg(request.capacityKg());
    }

    public VehicleResponse toResponse(Vehicle vehicle) {
        return new VehicleResponse(
                vehicle.getId(),
                vehicle.getName(),
                vehicle.getCapacityKg(),
                vehicle.getCreatedAt(),
                vehicle.getUpdatedAt());
    }
}
