package br.com.fabio.logistic.mapper;

import br.com.fabio.logistic.domain.Driver;
import br.com.fabio.logistic.dto.DriverRequest;
import br.com.fabio.logistic.dto.DriverResponse;
import org.springframework.stereotype.Component;

@Component
public class DriverMapper {

    public Driver toEntity(DriverRequest request) {
        Driver driver = new Driver();
        driver.setName(request.name());
        driver.setEmail(request.email());
        driver.setBirthday(request.birthday());
        driver.setCity(request.city());
        driver.setState(request.state());
        return driver;
    }

    public void updateEntity(Driver driver, DriverRequest request) {
        driver.setName(request.name());
        driver.setEmail(request.email());
        driver.setBirthday(request.birthday());
        driver.setCity(request.city());
        driver.setState(request.state());
    }

    public DriverResponse toResponse(Driver driver) {
        return new DriverResponse(
                driver.getId(),
                driver.getName(),
                driver.getEmail(),
                driver.getBirthday(),
                driver.getCity(),
                driver.getState(),
                driver.getCreatedAt(),
                driver.getUpdatedAt());
    }
}
