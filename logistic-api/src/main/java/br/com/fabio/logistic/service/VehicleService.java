package br.com.fabio.logistic.service;

import br.com.fabio.logistic.domain.Vehicle;
import br.com.fabio.logistic.dto.VehicleFilter;
import br.com.fabio.logistic.dto.VehicleRequest;
import br.com.fabio.logistic.dto.VehicleResponse;
import br.com.fabio.logistic.exception.NotFoundException;
import br.com.fabio.logistic.mapper.VehicleMapper;
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
    private final VehicleMapper vehicleMapper;

    public VehicleService(VehicleRepository vehicleRepository, VehicleMapper vehicleMapper) {
        this.vehicleRepository = vehicleRepository;
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

    @Transactional
    public void delete(UUID id) {
        Vehicle vehicle = getOrThrow(id);
        vehicleRepository.delete(vehicle);
    }

    Vehicle getOrThrow(UUID id) {
        return vehicleRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Veículo não encontrado para o id " + id));
    }
}
