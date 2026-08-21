package br.com.fabio.logistic.service;

import br.com.fabio.logistic.domain.Driver;
import br.com.fabio.logistic.domain.DriverVehicle;
import br.com.fabio.logistic.domain.Vehicle;
import br.com.fabio.logistic.dto.DriverFilter;
import br.com.fabio.logistic.dto.DriverRequest;
import br.com.fabio.logistic.dto.DeletionSummary;
import br.com.fabio.logistic.dto.DriverResponse;
import br.com.fabio.logistic.exception.ConflictException;
import br.com.fabio.logistic.exception.NotFoundException;
import br.com.fabio.logistic.mapper.DriverMapper;
import br.com.fabio.logistic.repository.DriverRepository;
import br.com.fabio.logistic.repository.DriverVehicleRepository;
import br.com.fabio.logistic.repository.RouteRepository;
import br.com.fabio.logistic.repository.VehicleRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Fonte única de verdade para regras de negócio de motorista. Controller e tools MCP delegam aqui. */
@Service
public class DriverService {

    private final DriverRepository driverRepository;
    private final VehicleRepository vehicleRepository;
    private final DriverVehicleRepository driverVehicleRepository;
    private final RouteRepository routeRepository;
    private final DriverMapper driverMapper;

    public DriverService(DriverRepository driverRepository,
                          VehicleRepository vehicleRepository,
                          DriverVehicleRepository driverVehicleRepository,
                          RouteRepository routeRepository,
                          DriverMapper driverMapper) {
        this.driverRepository = driverRepository;
        this.vehicleRepository = vehicleRepository;
        this.driverVehicleRepository = driverVehicleRepository;
        this.routeRepository = routeRepository;
        this.driverMapper = driverMapper;
    }

    @Transactional(readOnly = true)
    public Page<DriverResponse> search(DriverFilter filter, Pageable pageable) {
        Page<Driver> page = driverRepository.search(
                filter.name(), filter.email(), filter.city(), filter.state(),
                filter.birthdayFrom(), filter.birthdayTo(), filter.vehicleId(), pageable);
        return page.map(driverMapper::toResponse);
    }

    @Transactional(readOnly = true)
    public DriverResponse findById(UUID id) {
        return driverMapper.toResponse(getOrThrow(id));
    }

    @Transactional
    public DriverResponse create(DriverRequest request) {
        driverRepository.findByEmail(request.email()).ifPresent(d -> {
            throw new ConflictException("Já existe um motorista com o e-mail " + request.email());
        });
        Driver driver = driverMapper.toEntity(request);
        driver = driverRepository.save(driver);
        return driverMapper.toResponse(driver);
    }

    @Transactional
    public DriverResponse update(UUID id, DriverRequest request) {
        Driver driver = getOrThrow(id);
        driverRepository.findByEmail(request.email())
                .filter(other -> !other.getId().equals(id))
                .ifPresent(d -> {
                    throw new ConflictException("Já existe um motorista com o e-mail " + request.email());
                });
        driverMapper.updateEntity(driver, request);
        return driverMapper.toResponse(driver);
    }

    /**
     * Exclui o motorista. Recusa quando ele tem rotas: a FK route→driver é ON DELETE RESTRICT, e
     * deixar o banco estourar devolveria um erro de constraint no lugar de uma explicação. Os
     * vínculos com veículos caem por CASCADE — o retorno diz quantos, porque isso é efeito
     * colateral que o usuário precisa enxergar.
     */
    @Transactional
    public DeletionSummary delete(UUID id) {
        Driver driver = getOrThrow(id);
        long routes = routeRepository.countByDriverId(id);
        if (routes > 0) {
            throw new ConflictException("O motorista " + driver.getName() + " tem " + routes
                    + " rota(s) e não pode ser excluído. Cancele ou transfira as rotas antes.");
        }
        int links = driverVehicleRepository.findByDriverId(id).size();
        driverRepository.delete(driver);
        return new DeletionSummary(id, driver.getName(), links);
    }

    @Transactional
    public void linkVehicle(UUID driverId, UUID vehicleId) {
        Driver driver = getOrThrow(driverId);
        Vehicle vehicle = vehicleRepository.findById(vehicleId)
                .orElseThrow(() -> new NotFoundException("Veículo não encontrado para o id " + vehicleId));
        driverVehicleRepository.findByDriverIdAndVehicleId(driverId, vehicleId).ifPresent(dv -> {
            throw new ConflictException("Motorista já está vinculado a este veículo");
        });
        DriverVehicle link = new DriverVehicle();
        link.setDriver(driver);
        link.setVehicle(vehicle);
        driverVehicleRepository.save(link);
    }

    @Transactional
    public void unlinkVehicle(UUID driverId, UUID vehicleId) {
        DriverVehicle link = driverVehicleRepository.findByDriverIdAndVehicleId(driverId, vehicleId)
                .orElseThrow(() -> new NotFoundException("Vínculo não encontrado entre motorista e veículo"));
        driverVehicleRepository.delete(link);
    }

    Driver getOrThrow(UUID id) {
        return driverRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Motorista não encontrado para o id " + id));
    }
}
