package br.com.fabio.logistic.service;

import br.com.fabio.logistic.domain.Vehicle;
import br.com.fabio.logistic.dto.DeletionSummary;
import br.com.fabio.logistic.dto.VehicleFilter;
import br.com.fabio.logistic.dto.VehicleRequest;
import br.com.fabio.logistic.dto.VehicleResponse;
import br.com.fabio.logistic.exception.NotFoundException;
import br.com.fabio.logistic.mapper.VehicleMapper;
import br.com.fabio.logistic.repository.DriverVehicleRepository;
import br.com.fabio.logistic.repository.VehicleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Fonte única de verdade para regras de negócio de veículo. Controller e tools MCP delegam aqui. */
@Service
public class VehicleService {

    private final VehicleRepository vehicleRepository;
    private final DriverVehicleRepository driverVehicleRepository;
    private final VehicleMapper vehicleMapper;

    public VehicleService(VehicleRepository vehicleRepository,
                          DriverVehicleRepository driverVehicleRepository,
                          VehicleMapper vehicleMapper) {
        this.vehicleRepository = vehicleRepository;
        this.driverVehicleRepository = driverVehicleRepository;
        this.vehicleMapper = vehicleMapper;
    }

    @Transactional(readOnly = true)
    public Page<VehicleResponse> search(VehicleFilter filter, Pageable pageable) {
        Page<Vehicle> page = vehicleRepository.search(
                filter.name(), filter.capacityMinKg(), filter.capacityMaxKg(), filter.driverId(), pageable);
        return page.map(vehicleMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public VehicleResponse findById(UUID id) {
        Vehicle vehicle = getOrThrow(id);
        return vehicleMapper.toResponse(vehicle);
    }

    @Transactional
    public VehicleResponse create(VehicleRequest request) {
        Vehicle vehicle = vehicleMapper.toEntity(request);
        vehicle = vehicleRepository.save(vehicle);
        return vehicleMapper.toResponse(vehicle);
    }

    @Transactional
    public VehicleResponse update(UUID id, VehicleRequest request) {
        Vehicle vehicle = getOrThrow(id);
        vehicleMapper.updateEntity(vehicle, request);
        return vehicleMapper.toResponse(vehicle);
    }

    /**
     * Exclui o veículo. Nada bloqueia: o vínculo motorista↔veículo cai por CASCADE, e é só o
     * vínculo — motorista nenhum é apagado junto. O retorno diz quantos vínculos foram desfeitos,
     * porque o usuário confirma a exclusão de um veículo, não a de três associações.
     */
    @Transactional
    public DeletionSummary delete(UUID id) {
        Vehicle vehicle = getOrThrow(id);
        int links = driverVehicleRepository.findByVehicleId(id).size();
        vehicleRepository.delete(vehicle);
        return new DeletionSummary(id, vehicle.getName(), links);
    }

    Vehicle getOrThrow(UUID id) {
        return vehicleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Veículo não encontrado para o id " + id));
    }
}
