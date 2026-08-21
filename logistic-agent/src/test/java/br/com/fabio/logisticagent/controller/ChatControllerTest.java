package br.com.fabio.logisticagent.controller;

import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.ConfirmRequestDTO;
import br.com.fabio.logisticagent.dto.PendingActionDTO;
import br.com.fabio.logisticagent.service.ChatService;
import br.com.fabio.logisticagent.service.ConfirmationService;
import br.com.fabio.logisticagent.config.BackendHealthIndicator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChatService chatService;

    @MockitoBean
    private ConfirmationService confirmationService;

    @MockitoBean
    private BackendHealthIndicator backendHealth;

    @Test
    void chatReturnsAssistantMessage() throws Exception {
        when(chatService.respond(anyString(), any()))
                .thenReturn(new ChatMessageDTO("assistant", "resposta", null));

        mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("{\"message\":\"quantos motoristas existem?\",\"sessionId\":\"s1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("assistant"))
                .andExpect(jsonPath("$.content").value("resposta"))
                .andExpect(jsonPath("$.renderData").doesNotExist());
    }

    @Test
    void chatReturnsPendingActionWhenWriteNeedsConfirmation() throws Exception {
        when(chatService.respond(anyString(), any())).thenReturn(new ChatMessageDTO("assistant",
                "Vou cadastrar o motorista.", null,
                new PendingActionDTO("acao-1", "createDriver", "Cadastrar um novo motorista",
                        Map.of("Nome", "João Silva"), false)));

        mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("{\"message\":\"cadastre o motorista João Silva\",\"sessionId\":\"s1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingAction.id").value("acao-1"))
                .andExpect(jsonPath("$.pendingAction.summary").value("Cadastrar um novo motorista"))
                .andExpect(jsonPath("$.pendingAction.arguments.Nome").value("João Silva"));
    }

    @Test
    void confirmDelegatesToConfirmationService() throws Exception {
        when(confirmationService.resolve(new ConfirmRequestDTO("s1", "acao-1", true)))
                .thenReturn(new ChatMessageDTO("assistant", "✅ Ação confirmada e executada.", null));

        mockMvc.perform(post("/api/chat/confirm")
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\",\"actionId\":\"acao-1\",\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("✅ Ação confirmada e executada."));
    }

    @Test
    void healthReturnsRunning() throws Exception {
        when(backendHealth.health()).thenReturn(Health.up().build());

        mockMvc.perform(get("/api/chat/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.agent").value("logistic-agent"))
                .andExpect(jsonPath("$.backend").value("online"));
    }
}
