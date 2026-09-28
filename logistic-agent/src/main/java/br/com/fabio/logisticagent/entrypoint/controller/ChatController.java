package br.com.fabio.logisticagent.entrypoint.controller;

import br.com.fabio.logisticagent.core.usecase.chat.ConfirmActionUseCase;
import br.com.fabio.logisticagent.core.usecase.chat.SendMessageUseCase;
import br.com.fabio.logisticagent.core.usecase.health.CheckHealthUseCase;
import br.com.fabio.logisticagent.entrypoint.mapper.ResponseMapper;
import br.com.fabio.logisticagent.entrypoint.request.ChatRequest;
import br.com.fabio.logisticagent.entrypoint.request.ConfirmRequest;
import br.com.fabio.logisticagent.entrypoint.response.ChatResponse;
import br.com.fabio.logisticagent.entrypoint.response.HealthResponse;
import br.com.fabio.logisticagent.security.AuthenticatedUser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final SendMessageUseCase sendMessageUseCase;
    private final ConfirmActionUseCase confirmActionUseCase;
    private final CheckHealthUseCase checkHealthUseCase;

    public ChatController(SendMessageUseCase sendMessageUseCase, ConfirmActionUseCase confirmActionUseCase,
            CheckHealthUseCase checkHealthUseCase) {
        this.sendMessageUseCase = sendMessageUseCase;
        this.confirmActionUseCase = confirmActionUseCase;
        this.checkHealthUseCase = checkHealthUseCase;
    }

    @PostMapping
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest request) {
        String sessionId = request.sessionId() != null && !request.sessionId().isBlank()
                ? request.sessionId()
                : UUID.randomUUID().toString();
        String conversationId = AuthenticatedUser.conversationId(sessionId);
        return ResponseEntity.ok(ResponseMapper.toResponse(sendMessageUseCase.respond(request.message(), conversationId)));
    }

    @PostMapping("/confirm")
    public ResponseEntity<ChatResponse> confirm(@RequestBody ConfirmRequest request) {
        String conversationId = AuthenticatedUser.conversationId(request.sessionId());
        return ResponseEntity.ok(ResponseMapper.toResponse(
                confirmActionUseCase.execute(conversationId, request.actionId(), request.approved())));
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        boolean backendOnline = checkHealthUseCase.execute();
        return ResponseEntity.ok(new HealthResponse(backendOnline ? "running" : "degraded", "logistic-agent",
                backendOnline ? "online" : "offline"));
    }
}
