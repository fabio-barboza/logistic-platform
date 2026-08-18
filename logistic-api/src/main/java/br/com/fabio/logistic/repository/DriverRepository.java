package br.com.fabio.logistic.repository;

import br.com.fabio.logistic.domain.Driver;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

public interface DriverRepository extends JpaRepository<Driver, UUID> {

    Optional<Driver> findByEmail(String email);

    @Query("""
           SELECT d FROM Driver d
           WHERE (CAST(:name AS string)  IS NULL OR LOWER(d.name)  LIKE LOWER(CONCAT('%', CAST(:name AS string), '%')))
             AND (CAST(:email AS string) IS NULL OR LOWER(d.email) LIKE LOWER(CONCAT('%', CAST(:email AS string), '%')))
             AND (CAST(:city AS string)  IS NULL OR d.city  = :city)
             AND (CAST(:state AS string) IS NULL OR d.state = :state)
             AND (CAST(:birthdayFrom AS date) IS NULL OR d.birthday >= :birthdayFrom)
             AND (CAST(:birthdayTo   AS date) IS NULL OR d.birthday <= :birthdayTo)
             AND (CAST(:vehicleId AS uuid) IS NULL OR d.id IN (
                    SELECT dv.driver.id FROM DriverVehicle dv WHERE dv.vehicle.id = :vehicleId))
           """)
    Page<Driver> search(@Param("name") String name,
                         @Param("email") String email,
                         @Param("city") String city,
                         @Param("state") String state,
                         @Param("birthdayFrom") LocalDate birthdayFrom,
                         @Param("birthdayTo") LocalDate birthdayTo,
                         @Param("vehicleId") UUID vehicleId,
                         Pageable pageable);
}
