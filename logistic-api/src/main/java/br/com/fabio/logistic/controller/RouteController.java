package br.com.fabio.logistic.controller;

import br.com.fabio.logistic.domain.enums.RouteStatus;
import br.com.fabio.logistic.dto.OrderResponse;
import br.com.fabio.logistic.dto.RouteFilter;
import br.com.fabio.logistic.dto.RouteRequest;
import br.com.fabio.logistic.dto.RouteResponse;
import br.com.fabio.logistic.service.RouteService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
public class RouteController {

    private final RouteService routeService;

    public RouteController(RouteService routeService) {
        this.routeService = routeService;
    }

    @GetMapping("/api/routes")
    public Page<RouteResponse> search(@RequestParam(required = false) List<RouteStatus> status,
                                       @RequestParam(required = false) UUID driverId,
                                       @RequestParam(required = false) String driverName,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdFrom,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime createdTo,
                                       Pageable pageable) {
        RouteFilter filter = new RouteFilter(status, driverId, driverName, createdFrom, createdTo);
        return routeService.search(filter, pageable);
    }

    @GetMapping("/api/routes/{id}")
    public RouteResponse findById(@PathVariable UUID id) {
        return routeService.findById(id);
    }

    @PostMapping("/api/routes")
    public ResponseEntity<RouteResponse> create(@Valid @RequestBody RouteRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(routeService.create(request));
    }

    @PutMapping("/api/routes/{id}")
    public RouteResponse update(@PathVariable UUID id, @Valid @RequestBody RouteRequest request) {
        return routeService.update(id, request);
    }

    @DeleteMapping("/api/routes/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        routeService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/api/routes/{id}/orders")
    public List<OrderResponse> orders(@PathVariable UUID id) {
        return routeService.findOrders(id);
    }

    @PostMapping("/api/routes/{id}/orders/{orderId}")
    public OrderResponse assignOrder(@PathVariable UUID id, @PathVariable UUID orderId) {
        return routeService.assignOrder(id, orderId);
    }
}
