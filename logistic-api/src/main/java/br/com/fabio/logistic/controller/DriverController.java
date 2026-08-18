package br.com.fabio.logistic.controller;

import br.com.fabio.logistic.dto.DriverFilter;
import br.com.fabio.logistic.dto.DriverRequest;
import br.com.fabio.logistic.dto.DriverResponse;
import br.com.fabio.logistic.service.DriverService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
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

import java.time.LocalDate;
import java.util.UUID;

@RestController
public class DriverController {

    private final DriverService driverService;

    public DriverController(DriverService driverService) {
        this.driverService = driverService;
    }

    @GetMapping("/api/drivers")
    public Page<DriverResponse> search(@RequestParam(required = false) String name,
                                        @RequestParam(required = false) String email,
                                        @RequestParam(required = false) String city,
                                        @RequestParam(required = false) String state,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate birthdayFrom,
                                        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate birthdayTo,
                                        @RequestParam(required = false) UUID vehicleId,
                                        Pageable pageable) {
        DriverFilter filter = new DriverFilter(name, email, city, state, birthdayFrom, birthdayTo, vehicleId);
        return driverService.search(filter, pageable);
    }

    @GetMapping("/api/drivers/{id}")
    public DriverResponse findById(@PathVariable UUID id) {
        return driverService.findById(id);
    }

    @PostMapping("/api/drivers")
    public ResponseEntity<DriverResponse> create(@Valid @RequestBody DriverRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(driverService.create(request));
    }

    @PutMapping("/api/drivers/{id}")
    public DriverResponse update(@PathVariable UUID id, @Valid @RequestBody DriverRequest request) {
        return driverService.update(id, request);
    }

    @DeleteMapping("/api/drivers/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        driverService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/drivers/{id}/vehicles/{vehicleId}")
    public ResponseEntity<Void> linkVehicle(@PathVariable UUID id, @PathVariable UUID vehicleId) {
        driverService.linkVehicle(id, vehicleId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/api/drivers/{id}/vehicles/{vehicleId}")
    public ResponseEntity<Void> unlinkVehicle(@PathVariable UUID id, @PathVariable UUID vehicleId) {
        driverService.unlinkVehicle(id, vehicleId);
        return ResponseEntity.noContent().build();
    }
}
