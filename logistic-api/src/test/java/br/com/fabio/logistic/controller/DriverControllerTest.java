package br.com.fabio.logistic.controller;

import br.com.fabio.logistic.config.SecurityConfig;
import br.com.fabio.logistic.dto.DeletionSummary;
import br.com.fabio.logistic.dto.DriverRequest;
import br.com.fabio.logistic.dto.DriverResponse;
import br.com.fabio.logistic.exception.ConflictException;
import br.com.fabio.logistic.exception.NotFoundException;
import br.com.fabio.logistic.service.DriverService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DriverController.class)
@Import(SecurityConfig.class)
class DriverControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @MockitoBean
    private DriverService driverService;

    // A fábrica do JwtDecoder real (SecurityConfig) chama JwtDecoders.fromIssuerLocation, que
    // faz uma chamada HTTP ao Keycloak na criação do bean. O jwt() request post-processor abaixo
    // não passa pelo decoder — autentica direto no SecurityContext — então mockar aqui evita que
    // o @WebMvcTest dependa do Keycloak estar no ar.
    @MockitoBean
    private JwtDecoder jwtDecoder;

    private DriverResponse sampleResponse(UUID id) {
        return new DriverResponse(id, "Ana Silva", "ana@email.com", LocalDate.of(1990, 1, 1),
                "Campinas", "SP", LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void buscaPorIdDevolve200() throws Exception {
        UUID id = UUID.randomUUID();
        when(driverService.findById(id)).thenReturn(sampleResponse(id));

        mockMvc.perform(get("/api/drivers/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Ana Silva"));
    }

    @Test
    void buscaPorIdInexistenteDevolve404NoShapePadrao() throws Exception {
        UUID id = UUID.randomUUID();
        when(driverService.findById(id)).thenThrow(new NotFoundException("Motorista não encontrado para o id " + id));

        mockMvc.perform(get("/api/drivers/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_read"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Motorista não encontrado para o id " + id));
    }

    @Test
    void criacaoComPayloadInvalidoDevolve400NoShapePadrao() throws Exception {
        DriverRequest invalido = new DriverRequest("", "not-an-email", null, "", "SPX");

        mockMvc.perform(post("/api/drivers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_write")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(invalido)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Dados inválidos"));
    }

    @Test
    void buscaListaDevolve200() throws Exception {
        UUID id = UUID.randomUUID();
        when(driverService.search(any(), any())).thenReturn(new PageImpl<>(List.of(sampleResponse(id))));

        mockMvc.perform(get("/api/drivers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Ana Silva"));
    }

    @Test
    void exclusaoDevolve204() throws Exception {
        UUID id = UUID.randomUUID();
        when(driverService.delete(id)).thenReturn(new DeletionSummary(id, "João Ribeiro", 2));

        mockMvc.perform(delete("/api/drivers/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_write"))))
                .andExpect(status().isNoContent());
    }

    /** Motorista com rota é conflito, não erro interno: a FK route→driver é RESTRICT. */
    @Test
    void exclusaoDeMotoristaComRotasDevolve409() throws Exception {
        UUID id = UUID.randomUUID();
        when(driverService.delete(id))
                .thenThrow(new ConflictException("O motorista João Ribeiro tem 3 rota(s) e não pode ser excluído."));

        mockMvc.perform(delete("/api/drivers/{id}", id)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_write"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));
    }

    @Test
    void buscaSemTokenDevolve401() throws Exception {
        mockMvc.perform(get("/api/drivers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void buscaComRoleReadDevolve200() throws Exception {
        when(driverService.search(any(), any())).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/drivers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_read"))))
                .andExpect(status().isOk());
    }

    @Test
    void criacaoComApenasRoleReadDevolve403() throws Exception {
        DriverRequest request = new DriverRequest("Ana Silva", "ana@email.com", LocalDate.of(1990, 1, 1), "Campinas", "SP");

        mockMvc.perform(post("/api/drivers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_read")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void criacaoComRoleWriteDevolve201() throws Exception {
        UUID id = UUID.randomUUID();
        DriverRequest request = new DriverRequest("Ana Silva", "ana@email.com", LocalDate.of(1990, 1, 1), "Campinas", "SP");
        when(driverService.create(request)).thenReturn(sampleResponse(id));

        mockMvc.perform(post("/api/drivers")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_write")))
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }
}
