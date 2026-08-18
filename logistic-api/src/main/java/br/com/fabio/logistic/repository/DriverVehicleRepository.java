package br.com.fabio.logistic.repository;

import br.com.fabio.logistic.domain.DriverVehicle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DriverVehicleRepository extends JpaRepository<DriverVehicle, UUID> {

    List<DriverVehicle> findByDriverId(UUID driverId);

    List<DriverVehicle> findByVehicleId(UUID vehicleId);

    Optional<DriverVehicle> findByDriverIdAndVehicleId(UUID driverId, UUID vehicleId);
}
