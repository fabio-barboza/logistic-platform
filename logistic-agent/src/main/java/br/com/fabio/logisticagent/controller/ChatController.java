package br.com.fabio.logisticagent.controller;

import br.com.fabio.logisticagent.config.BackendHealthIndicator;
import br.com.fabio.logisticagent.dto.ChatMessageDTO;
import br.com.fabio.logisticagent.dto.ChatRequestDTO;
import br.com.fabio.logisticagent.service.ChatService;
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
    private final BackendHealthIndicator backendHealth;

    public ChatController(ChatService chatService, BackendHealthIndicator backendHealth) {
        this.chatService = chatService;
        this.backendHealth = backendHealth;
    }

    @PostMapping
    public ResponseEntity<ChatMessageDTO> chat(@RequestBody ChatRequestDTO request) {
        String sessionId = request.sessionId() != null && !request.sessionId().isBlank()
                ? request.sessionId()
                : UUID.randomUUID().toString();
        return ResponseEntity.ok(chatService.respond(request.message(), sessionId));
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
