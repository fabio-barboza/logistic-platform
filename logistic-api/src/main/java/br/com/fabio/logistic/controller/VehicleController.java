package br.com.fabio.logistic.controller;

import br.com.fabio.logistic.dto.VehicleFilter;
import br.com.fabio.logistic.dto.VehicleRequest;
import br.com.fabio.logistic.dto.VehicleResponse;
import br.com.fabio.logistic.service.VehicleService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class VehicleController {

    private final VehicleService vehicleService;

    public VehicleController(VehicleService vehicleService) {
        this.vehicleService = vehicleService;
    }

    @GetMapping("/api/vehicles")
    public Page<VehicleResponse> search(@RequestParam(required = false) String name,
                                         @RequestParam(required = false) Integer capacityMinKg,
                                         @RequestParam(required = false) Integer capacityMaxKg,
                                         @RequestParam(required = false) UUID driverId,
                                         Pageable pageable) {
        VehicleFilter filter = new VehicleFilter(name, capacityMinKg, capacityMaxKg, driverId);
        return vehicleService.search(filter, pageable);
    }

    @GetMapping("/api/vehicles/{id}")
    public VehicleResponse findById(@PathVariable UUID id) {
        return vehicleService.findById(id);
    }

    @PostMapping("/api/vehicles")
    public ResponseEntity<VehicleResponse> create(@Valid @RequestBody VehicleRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(vehicleService.create(request));
    }

    @PutMapping("/api/vehicles/{id}")
    public VehicleResponse update(@PathVariable UUID id, @Valid @RequestBody VehicleRequest request) {
        return vehicleService.update(id, request);
    }

    @DeleteMapping("/api/vehicles/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        vehicleService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
