package br.com.fabio.logistic.service;

import br.com.fabio.logistic.domain.Driver;
import br.com.fabio.logistic.domain.Order;
import br.com.fabio.logistic.domain.Route;
import br.com.fabio.logistic.domain.enums.RouteStatus;
import br.com.fabio.logistic.dto.OrderResponse;
import br.com.fabio.logistic.dto.RouteFilter;
import br.com.fabio.logistic.dto.RouteRequest;
import br.com.fabio.logistic.dto.RouteResponse;
import br.com.fabio.logistic.exception.NotFoundException;
import br.com.fabio.logistic.mapper.OrderMapper;
import br.com.fabio.logistic.mapper.RouteMapper;
import br.com.fabio.logistic.repository.DriverRepository;
import br.com.fabio.logistic.repository.OrderRepository;
import br.com.fabio.logistic.repository.RouteRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Fonte única de verdade para regras de negócio de rota. Controller e tools MCP delegam aqui. */
@Service
public class RouteService {

    private final RouteRepository routeRepository;
    private final DriverRepository driverRepository;
    private final OrderRepository orderRepository;
    private final RouteMapper routeMapper;
    private final OrderMapper orderMapper;

    public RouteService(RouteRepository routeRepository,
                         DriverRepository driverRepository,
                         OrderRepository orderRepository,
                         RouteMapper routeMapper,
                         OrderMapper orderMapper) {
        this.routeRepository = routeRepository;
        this.driverRepository = driverRepository;
        this.orderRepository = orderRepository;
        this.routeMapper = routeMapper;
        this.orderMapper = orderMapper;
    }

    @Transactional(readOnly = true)
    public Page<RouteResponse> search(RouteFilter filter, Pageable pageable) {
        Page<Route> page = routeRepository.search(
                filter.status(), filter.driverId(), filter.driverName(),
                filter.createdFrom(), filter.createdTo(), pageable);
        return page.map(routeMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public RouteResponse findById(UUID id) {
        return routeMapper.toResponse(getOrThrow(id));
    }

    @Transactional
    public RouteResponse create(RouteRequest request) {
        Driver driver = findDriverOrThrow(request.driverId());
        Route route = routeMapper.toEntity(request, driver);
        route = routeRepository.save(route);
        return routeMapper.toResponse(route);
    }

    @Transactional
    public RouteResponse update(UUID id, RouteRequest request) {
        Route route = getOrThrow(id);
        Driver driver = findDriverOrThrow(request.driverId());
        routeMapper.updateEntity(route, request, driver);
        return routeMapper.toResponse(route);
    }

    @Transactional
    public void delete(UUID id) {
        Route route = getOrThrow(id);
        routeRepository.delete(route);
    }

    @Transactional
    public RouteResponse updateStatus(UUID id, RouteStatus status) {
        Route route = getOrThrow(id);
        route.setStatus(status);
        return routeMapper.toResponse(route);
    }

    @Transactional(readOnly = true)
    public List<OrderResponse> findOrders(UUID routeId) {
        getOrThrow(routeId);
        return orderRepository.findByRouteId(routeId).stream().map(orderMapper::toResponse).toList();
    }

    @Transactional
    public OrderResponse assignOrder(UUID routeId, UUID orderId) {
        Route route = getOrThrow(routeId);
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new NotFoundException("Pedido não encontrado para o id " + orderId));
        order.setRoute(route);
        return orderMapper.toResponse(order);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> countByStatus() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : routeRepository.countByStatus()) {
            result.put(String.valueOf(row[0]), (Long) row[1]);
        }
        return result;
    }

    @Transactional(readOnly = true)
    public Map<String, Long> countByDriver() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Object[] row : routeRepository.countByDriver()) {
            result.put(String.valueOf(row[0]), (Long) row[1]);
        }
        return result;
    }

    private Driver findDriverOrThrow(UUID driverId) {
        return driverRepository.findById(driverId)
                .orElseThrow(() -> new NotFoundException("Motorista não encontrado para o id " + driverId));
    }

    Route getOrThrow(UUID id) {
        return routeRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Rota não encontrada para o id " + id));
    }
}
