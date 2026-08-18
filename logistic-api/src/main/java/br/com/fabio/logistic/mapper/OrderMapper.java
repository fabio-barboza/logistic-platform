package br.com.fabio.logistic.mapper;

import br.com.fabio.logistic.domain.Order;
import br.com.fabio.logistic.domain.Route;
import br.com.fabio.logistic.dto.OrderRequest;
import br.com.fabio.logistic.dto.OrderResponse;
import org.springframework.stereotype.Component;

@Component
public class OrderMapper {

    public Order toEntity(OrderRequest request, Route route) {
        Order order = new Order();
        order.setRoute(route);
        order.setZipCode(request.zipCode());
        order.setNeighborhood(request.neighborhood());
        order.setCity(request.city());
        order.setState(request.state());
        order.setStatus(request.status());
        return order;
    }

    public void updateEntity(Order order, OrderRequest request, Route route) {
        order.setRoute(route);
        order.setZipCode(request.zipCode());
        order.setNeighborhood(request.neighborhood());
        order.setCity(request.city());
        order.setState(request.state());
        order.setStatus(request.status());
    }

    public OrderResponse toResponse(Order order) {
        return new OrderResponse(
                order.getId(),
                order.getRoute() != null ? order.getRoute().getId() : null,
                order.getZipCode(),
                order.getNeighborhood(),
                order.getCity(),
                order.getState(),
                order.getStatus(),
                order.getCreatedAt(),
                order.getUpdatedAt());
    }
}
