package br.com.fabio.logistic.service;

import br.com.fabio.logistic.domain.Order;
import br.com.fabio.logistic.domain.Route;
import br.com.fabio.logistic.domain.enums.OrderStatus;
import br.com.fabio.logistic.dto.OrderFilter;
import br.com.fabio.logistic.dto.OrderRequest;
import br.com.fabio.logistic.dto.OrderResponse;
import br.com.fabio.logistic.exception.NotFoundException;
import br.com.fabio.logistic.mapper.OrderMapper;
import br.com.fabio.logistic.repository.OrderRepository;
import br.com.fabio.logistic.repository.RouteRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Fonte única de verdade para regras de negócio de pedido. Controller e tools MCP delegam aqui. */
@Service
public class OrderService {

    private final OrderRepository orderRepository;
    private final RouteRepository routeRepository;
    private final OrderMapper orderMapper;

    public OrderService(OrderRepository orderRepository, RouteRepository routeRepository, OrderMapper orderMapper) {
        this.orderRepository = orderRepository;
        this.routeRepository = routeRepository;
        this.orderMapper = orderMapper;
    }

    @Transactional(readOnly = true)
    public Page<OrderResponse> search(OrderFilter filter, Pageable pageable) {
        Page<Order> page = orderRepository.search(
                filter.status(), filter.routeId(), filter.city(), filter.state(),
                filter.neighborhood(), filter.zipCode(), filter.createdFrom(), filter.createdTo(),
                filter.unassigned(), pageable);
        return page.map(orderMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(UUID id) {
        return orderMapper.toResponse(getOrThrow(id));
    }

    @Transactional
    public OrderResponse create(OrderRequest request) {
        Route route = findRouteIfPresent(request.routeId());
        Order order = orderMapper.toEntity(request, route);
        order = orderRepository.save(order);
        return orderMapper.toResponse(order);
    }

    @Transactional
    public OrderResponse update(UUID id, OrderRequest request) {
        Order order = getOrThrow(id);
        Route route = findRouteIfPresent(request.routeId());
        orderMapper.updateEntity(order, request, route);
        return orderMapper.toResponse(order);
    }

    @Transactional
    public void delete(UUID id) {
        Order order = getOrThrow(id);
        orderRepository.delete(order);
    }

    @Transactional
    public OrderResponse updateStatus(UUID id, OrderStatus status) {
        Order order = getOrThrow(id);
        order.setStatus(status);
        return orderMapper.toResponse(order);
    }

    @Transactional(readOnly = true)
    public Map<String, Long> countBy(String groupBy) {
        Map<String, Long> result = new LinkedHashMap<>();
        Iterable<Object[]> rows = switch (groupBy) {
            case "status" -> orderRepository.countByStatus();
            case "state" -> orderRepository.countByState();
            case "city" -> orderRepository.countByCity();
            case "neighborhood" -> orderRepository.countByNeighborhood();
            default -> throw new IllegalArgumentException(
                    "Agrupamento inválido: " + groupBy + ". Use status, state, city ou neighborhood.");
        };
        for (Object[] row : rows) {
            result.put(String.valueOf(row[0]), (Long) row[1]);
        }
        return result;
    }

    private Route findRouteIfPresent(UUID routeId) {
        if (routeId == null) {
            return null;
        }
        return routeRepository.findById(routeId)
                .orElseThrow(() -> new NotFoundException("Rota não encontrada para o id " + routeId));
    }

    Order getOrThrow(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Pedido não encontrado para o id " + id));
    }
}
