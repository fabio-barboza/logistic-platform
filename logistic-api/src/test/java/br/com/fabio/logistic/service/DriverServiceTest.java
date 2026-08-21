package br.com.fabio.logistic.service;

import br.com.fabio.logistic.domain.Driver;
import br.com.fabio.logistic.domain.DriverVehicle;
import br.com.fabio.logistic.dto.DeletionSummary;
import br.com.fabio.logistic.exception.ConflictException;
import br.com.fabio.logistic.exception.NotFoundException;
import br.com.fabio.logistic.mapper.DriverMapper;
import br.com.fabio.logistic.repository.DriverRepository;
import br.com.fabio.logistic.repository.DriverVehicleRepository;
import br.com.fabio.logistic.repository.RouteRepository;
import br.com.fabio.logistic.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DriverServiceTest {

    private final DriverRepository driverRepository = mock(DriverRepository.class);
    private final DriverVehicleRepository driverVehicleRepository = mock(DriverVehicleRepository.class);
    private final RouteRepository routeRepository = mock(RouteRepository.class);

    private final UUID id = UUID.randomUUID();
    private final Driver driver = new Driver();

    private DriverService driverService;

    @BeforeEach
    void setUp() {
        driver.setId(id);
        driver.setName("João Ribeiro");
        driverService = new DriverService(driverRepository, mock(VehicleRepository.class),
                driverVehicleRepository, routeRepository, new DriverMapper());
        when(driverRepository.findById(id)).thenReturn(Optional.of(driver));
    }

    @Test
    void exclusaoDevolveQuantosVinculosCairam() {
        when(routeRepository.countByDriverId(id)).thenReturn(0L);
        when(driverVehicleRepository.findByDriverId(id))
                .thenReturn(List.of(new DriverVehicle(), new DriverVehicle()));

        DeletionSummary summary = driverService.delete(id);

        assertThat(summary.name()).isEqualTo("João Ribeiro");
        assertThat(summary.removedLinks()).isEqualTo(2);
        verify(driverRepository).delete(driver);
    }

    /** A FK route→driver é RESTRICT: sem esta checagem o banco estoura com erro de constraint. */
    @Test
    void motoristaComRotasNaoPodeSerExcluido() {
        when(routeRepository.countByDriverId(id)).thenReturn(3L);

        assertThatThrownBy(() -> driverService.delete(id))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("3 rota(s)");
        verify(driverRepository, never()).delete(driver);
    }

    @Test
    void exclusaoDeIdInexistenteDevolveNotFound() {
        UUID desconhecido = UUID.randomUUID();
        when(driverRepository.findById(desconhecido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> driverService.delete(desconhecido))
                .isInstanceOf(NotFoundException.class);
    }
}
