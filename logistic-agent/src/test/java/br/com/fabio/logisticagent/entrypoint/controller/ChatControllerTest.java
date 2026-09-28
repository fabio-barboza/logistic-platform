package br.com.fabio.logisticagent.entrypoint.controller;

import br.com.fabio.logisticagent.config.SecurityConfig;
import br.com.fabio.logisticagent.core.domain.chat.ChatMessage;
import br.com.fabio.logisticagent.core.domain.chat.PendingAction;
import br.com.fabio.logisticagent.core.usecase.chat.ConfirmActionUseCase;
import br.com.fabio.logisticagent.core.usecase.chat.SendMessageUseCase;
import br.com.fabio.logisticagent.core.usecase.health.CheckHealthUseCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ChatController.class)
@Import(SecurityConfig.class)
class ChatControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SendMessageUseCase sendMessageUseCase;

    @MockitoBean
    private ConfirmActionUseCase confirmActionUseCase;

    @MockitoBean
    private CheckHealthUseCase checkHealthUseCase;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void chatReturnsAssistantMessage() throws Exception {
        when(sendMessageUseCase.respond(anyString(), any()))
                .thenReturn(new ChatMessage("assistant", "resposta", null));

        mockMvc.perform(post("/api/chat")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_chat")))
                        .contentType("application/json")
                        .content("{\"message\":\"quantos motoristas existem?\",\"sessionId\":\"s1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("assistant"))
                .andExpect(jsonPath("$.content").value("resposta"))
                .andExpect(jsonPath("$.renderData").doesNotExist());

        verify(sendMessageUseCase).respond("quantos motoristas existem?", "user|s1");
    }

    @Test
    void chatReturnsPendingActionWhenWriteNeedsConfirmation() throws Exception {
        when(sendMessageUseCase.respond(anyString(), any())).thenReturn(new ChatMessage("assistant",
                "Vou cadastrar o motorista.", null,
                new PendingAction("acao-1", "user|s1", "createDriver", "{\"name\":\"João Silva\"}",
                        Instant.now(), Map.of())));

        mockMvc.perform(post("/api/chat")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_chat")))
                        .contentType("application/json")
                        .content("{\"message\":\"cadastre o motorista João Silva\",\"sessionId\":\"s1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingAction.id").value("acao-1"))
                .andExpect(jsonPath("$.pendingAction.summary").value("Cadastrar um novo motorista"))
                .andExpect(jsonPath("$.pendingAction.arguments.Nome").value("João Silva"));
    }

    @Test
    void confirmDelegatesToConfirmActionUseCase() throws Exception {
        when(confirmActionUseCase.execute("user|s1", "acao-1", true))
                .thenReturn(new ChatMessage("assistant", "✅ Ação confirmada e executada.", null));

        mockMvc.perform(post("/api/chat/confirm")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_write")))
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\",\"actionId\":\"acao-1\",\"approved\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("✅ Ação confirmada e executada."));
    }

    @Test
    void healthReturnsRunning() throws Exception {
        when(checkHealthUseCase.execute()).thenReturn(true);

        mockMvc.perform(get("/api/chat/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("running"))
                .andExpect(jsonPath("$.agent").value("logistic-agent"))
                .andExpect(jsonPath("$.backend").value("online"));
    }

    @Test
    void chatWithoutTokenIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType("application/json")
                        .content("{\"message\":\"oi\",\"sessionId\":\"s1\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void chatWithOnlyReadRoleIsForbidden() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_read")))
                        .contentType("application/json")
                        .content("{\"message\":\"oi\",\"sessionId\":\"s1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void confirmWithOnlyChatRoleIsForbidden() throws Exception {
        mockMvc.perform(post("/api/chat/confirm")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_chat")))
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\",\"actionId\":\"acao-1\",\"approved\":true}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void confirmWithWriteRoleSucceeds() throws Exception {
        when(confirmActionUseCase.execute("user|s1", "acao-1", true))
                .thenReturn(new ChatMessage("assistant", "✅ Ação confirmada e executada.", null));

        mockMvc.perform(post("/api/chat/confirm")
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_write")))
                        .contentType("application/json")
                        .content("{\"sessionId\":\"s1\",\"actionId\":\"acao-1\",\"approved\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    void healthWithoutTokenIsOk() throws Exception {
        when(checkHealthUseCase.execute()).thenReturn(true);

        mockMvc.perform(get("/api/chat/health"))
                .andExpect(status().isOk());
    }
}
