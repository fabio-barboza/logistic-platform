package br.com.fabio.logistic.repository;

import br.com.fabio.logistic.domain.Order;
import br.com.fabio.logistic.domain.enums.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface OrderRepository extends JpaRepository<Order, UUID> {

    List<Order> findByRouteId(UUID routeId);

    @Query("""
           SELECT o FROM Order o
           WHERE (:status IS NULL OR o.status IN :status)
             AND (CAST(:routeId AS uuid) IS NULL OR o.route.id = :routeId)
             AND (CAST(:city AS string) IS NULL OR o.city = :city)
             AND (CAST(:state AS string) IS NULL OR o.state = :state)
             AND (CAST(:neighborhood AS string) IS NULL OR LOWER(o.neighborhood) LIKE LOWER(CONCAT('%', CAST(:neighborhood AS string), '%')))
             AND (CAST(:zipCode AS string) IS NULL OR o.zipCode = :zipCode)
             AND (CAST(:createdFrom AS timestamp) IS NULL OR o.createdAt >= :createdFrom)
             AND (CAST(:createdTo   AS timestamp) IS NULL OR o.createdAt <= :createdTo)
             AND (CAST(:unassigned AS boolean) IS NULL OR (:unassigned = TRUE AND o.route IS NULL) OR (:unassigned = FALSE AND o.route IS NOT NULL))
           """)
    Page<Order> search(@Param("status") List<OrderStatus> status,
                        @Param("routeId") UUID routeId,
                        @Param("city") String city,
                        @Param("state") String state,
                        @Param("neighborhood") String neighborhood,
                        @Param("zipCode") String zipCode,
                        @Param("createdFrom") LocalDateTime createdFrom,
                        @Param("createdTo") LocalDateTime createdTo,
                        @Param("unassigned") Boolean unassigned,
                        Pageable pageable);

    @Query("""
           SELECT o.status AS status, COUNT(o) AS total
           FROM Order o
           GROUP BY o.status
           """)
    List<Object[]> countByStatus();

    @Query("""
           SELECT o.state AS state, COUNT(o) AS total
           FROM Order o
           GROUP BY o.state
           """)
    List<Object[]> countByState();

    @Query("""
           SELECT o.city AS city, COUNT(o) AS total
           FROM Order o
           GROUP BY o.city
           """)
    List<Object[]> countByCity();

    @Query("""
           SELECT o.neighborhood AS neighborhood, COUNT(o) AS total
           FROM Order o
           GROUP BY o.neighborhood
           """)
    List<Object[]> countByNeighborhood();
}
