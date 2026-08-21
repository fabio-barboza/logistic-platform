package br.com.fabio.logistic.repository;

import br.com.fabio.logistic.domain.Vehicle;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface VehicleRepository extends JpaRepository<Vehicle, UUID> {

    @Query("""
           SELECT v FROM Vehicle v
           WHERE (CAST(:name AS string) IS NULL OR LOWER(v.name) LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
             AND (CAST(:capacityMinKg AS integer) IS NULL OR v.capacityKg >= :capacityMinKg)
             AND (CAST(:capacityMaxKg AS integer) IS NULL OR v.capacityKg <= :capacityMaxKg)
             AND (CAST(:driverId AS uuid) IS NULL OR v.id IN (
                    SELECT dv.vehicle.id FROM DriverVehicle dv WHERE dv.driver.id = :driverId))
           """)
    Page<Vehicle> search(@Param("name") String name,
                          @Param("capacityMinKg") Integer capacityMinKg,
                          @Param("capacityMaxKg") Integer capacityMaxKg,
                          @Param("driverId") UUID driverId,
                          Pageable pageable);
}
