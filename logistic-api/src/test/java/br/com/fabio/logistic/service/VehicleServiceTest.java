package br.com.fabio.logistic.service;

import br.com.fabio.logistic.domain.DriverVehicle;
import br.com.fabio.logistic.domain.Vehicle;
import br.com.fabio.logistic.dto.DeletionSummary;
import br.com.fabio.logistic.exception.NotFoundException;
import br.com.fabio.logistic.mapper.VehicleMapper;
import br.com.fabio.logistic.repository.DriverVehicleRepository;
import br.com.fabio.logistic.repository.VehicleRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VehicleServiceTest {

    private final VehicleRepository vehicleRepository = mock(VehicleRepository.class);
    private final DriverVehicleRepository driverVehicleRepository = mock(DriverVehicleRepository.class);

    private final UUID id = UUID.randomUUID();
    private final Vehicle vehicle = new Vehicle();

    private VehicleService vehicleService;

    @BeforeEach
    void setUp() {
        vehicle.setId(id);
        vehicle.setName("Truck X");
        vehicleService = new VehicleService(vehicleRepository, driverVehicleRepository, new VehicleMapper());
        when(vehicleRepository.findById(id)).thenReturn(Optional.of(vehicle));
    }

    /** Veículo sai mesmo vinculado: o CASCADE desfaz o vínculo, não o motorista. */
    @Test
    void exclusaoDesfazVinculosEOsConta() {
        when(driverVehicleRepository.findByVehicleId(id)).thenReturn(List.of(new DriverVehicle()));

        DeletionSummary summary = vehicleService.delete(id);

        assertThat(summary.name()).isEqualTo("Truck X");
        assertThat(summary.removedLinks()).isEqualTo(1);
        verify(vehicleRepository).delete(vehicle);
    }

    @Test
    void exclusaoDeIdInexistenteDevolveNotFound() {
        UUID desconhecido = UUID.randomUUID();
        when(vehicleRepository.findById(desconhecido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> vehicleService.delete(desconhecido))
                .isInstanceOf(NotFoundException.class);
    }
}
