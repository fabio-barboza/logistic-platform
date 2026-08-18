package br.com.fabio.logistic.controller;

import br.com.fabio.logistic.dto.VehicleRequest;
import br.com.fabio.logistic.dto.VehicleResponse;
import br.com.fabio.logistic.exception.NotFoundException;
import br.com.fabio.logistic.service.VehicleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VehicleController.class)
class VehicleControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @MockitoBean
    private VehicleService vehicleService;

    @Test
    void criacaoComPayloadValidoDevolve201() throws Exception {
        UUID id = UUID.randomUUID();
        VehicleRequest request = new VehicleRequest("Van Sprinter", 1200);
        VehicleResponse response = new VehicleResponse(id, "Van Sprinter", 1200, LocalDateTime.now(), LocalDateTime.now());
        when(vehicleService.create(request)).thenReturn(response);

        mockMvc.perform(post("/api/vehicles")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Van Sprinter"));
    }

    @Test
    void criacaoComPayloadInvalidoDevolve400() throws Exception {
        VehicleRequest invalido = new VehicleRequest("", -5);

        mockMvc.perform(post("/api/vehicles")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(invalido)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));
    }

    @Test
    void buscaPorIdInexistenteDevolve404() throws Exception {
        UUID id = UUID.randomUUID();
        when(vehicleService.findById(id)).thenThrow(new NotFoundException("Veículo não encontrado para o id " + id));

        mockMvc.perform(get("/api/vehicles/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
