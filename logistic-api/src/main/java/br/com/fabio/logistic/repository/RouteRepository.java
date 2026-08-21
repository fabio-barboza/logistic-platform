package br.com.fabio.logistic.repository;

import br.com.fabio.logistic.domain.Route;
import br.com.fabio.logistic.domain.enums.RouteStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface RouteRepository extends JpaRepository<Route, UUID> {

    /** Rotas do motorista. A FK route→driver é ON DELETE RESTRICT: com rota, o motorista não sai. */
    long countByDriverId(UUID driverId);

    @Query("""
           SELECT r FROM Route r
           WHERE (:status IS NULL OR r.status IN :status)
             AND (CAST(:driverId AS uuid) IS NULL OR r.driver.id = :driverId)
             AND (CAST(:driverName AS string) IS NULL OR LOWER(r.driver.name) LIKE LOWER(CONCAT('%', CAST(:driverName AS string), '%')))
             AND (CAST(:createdFrom AS timestamp) IS NULL OR r.createdAt >= :createdFrom)
             AND (CAST(:createdTo   AS timestamp) IS NULL OR r.createdAt <= :createdTo)
           """)
    Page<Route> search(@Param("status") List<RouteStatus> status,
                        @Param("driverId") UUID driverId,
                        @Param("driverName") String driverName,
                        @Param("createdFrom") LocalDateTime createdFrom,
                        @Param("createdTo") LocalDateTime createdTo,
                        Pageable pageable);

    @Query("""
           SELECT r.status AS status, COUNT(r) AS total
           FROM Route r
           GROUP BY r.status
           """)
    List<Object[]> countByStatus();

    @Query("""
           SELECT r.driver.name AS driverName, COUNT(r) AS total
           FROM Route r
           GROUP BY r.driver.name
           """)
    List<Object[]> countByDriver();
}
