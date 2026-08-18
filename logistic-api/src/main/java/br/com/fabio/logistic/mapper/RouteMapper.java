package br.com.fabio.logistic.mapper;

import br.com.fabio.logistic.domain.Driver;
import br.com.fabio.logistic.domain.Route;
import br.com.fabio.logistic.dto.RouteRequest;
import br.com.fabio.logistic.dto.RouteResponse;
import org.springframework.stereotype.Component;

@Component
public class RouteMapper {

    public Route toEntity(RouteRequest request, Driver driver) {
        Route route = new Route();
        route.setDriver(driver);
        route.setStatus(request.status());
        return route;
    }

    public void updateEntity(Route route, RouteRequest request, Driver driver) {
        route.setDriver(driver);
        route.setStatus(request.status());
    }

    public RouteResponse toResponse(Route route) {
        return new RouteResponse(
                route.getId(),
                route.getDriver().getId(),
                route.getDriver().getName(),
                route.getStatus(),
                route.getCreatedAt(),
                route.getUpdatedAt());
    }
}
