package br.com.fabio.logistic.controller;

import br.com.fabio.logistic.domain.enums.OrderStatus;
import br.com.fabio.logistic.dto.OrderResponse;
import br.com.fabio.logistic.exception.NotFoundException;
import br.com.fabio.logistic.service.OrderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderService orderService;

    private OrderResponse sampleResponse(UUID id) {
        return new OrderResponse(id, null, "13000-000", "Centro", "Campinas", "SP",
                OrderStatus.DELIVERED, LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void buscaComFiltroDevolve200() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.search(any(), any())).thenReturn(new PageImpl<>(List.of(sampleResponse(id))));

        mockMvc.perform(get("/api/orders").param("state", "SP").param("status", "DELIVERED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].state").value("SP"));
    }

    @Test
    void buscaSemParametroTrazTudoPaginado() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.search(any(), any())).thenReturn(new PageImpl<>(List.of(sampleResponse(id))));

        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }

    @Test
    void buscaPorIdInexistenteDevolve404() throws Exception {
        UUID id = UUID.randomUUID();
        when(orderService.findById(id)).thenThrow(new NotFoundException("Pedido não encontrado para o id " + id));

        mockMvc.perform(get("/api/orders/{id}", id))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
