package br.com.fabio.logisticagent.controller;

import br.com.fabio.logisticagent.config.BackendHealthIndicator;
import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.ChatRequestDTO;
import br.com.fabio.logisticagent.dto.ConfirmRequestDTO;
import br.com.fabio.logisticagent.service.ChatService;
import br.com.fabio.logisticagent.service.ConfirmationService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;
    private final ConfirmationService confirmationService;
    private final BackendHealthIndicator backendHealth;

    public ChatController(ChatService chatService, ConfirmationService confirmationService,
            BackendHealthIndicator backendHealth) {
        this.chatService = chatService;
        this.confirmationService = confirmationService;
        this.backendHealth = backendHealth;
    }

    @PostMapping
    public ResponseEntity<ChatMessageDTO> chat(@RequestBody ChatRequestDTO request) {
        String sessionId = request.sessionId() != null && !request.sessionId().isBlank()
                ? request.sessionId()
                : UUID.randomUUID().toString();
        return ResponseEntity.ok(chatService.respond(request.message(), sessionId));
    }

    /**
     * Resposta do usuário a uma ação de escrita pendente. Não passa pela LLM: executa (ou
     * descarta) a chamada de tool já registrada, com os argumentos originais.
     */
    @PostMapping("/confirm")
    public ResponseEntity<ChatMessageDTO> confirm(@RequestBody ConfirmRequestDTO request) {
        return ResponseEntity.ok(confirmationService.resolve(request));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Health health = backendHealth.health();
        boolean backendOnline = "UP".equals(health.getStatus().toString());

        Map<String, Object> healthResponse = new LinkedHashMap<>();
        healthResponse.put("status", backendOnline ? "running" : "degraded");
        healthResponse.put("agent", "logistic-agent");
        healthResponse.put("backend", backendOnline ? "online" : "offline");

        return ResponseEntity.ok(healthResponse);
    }
}
