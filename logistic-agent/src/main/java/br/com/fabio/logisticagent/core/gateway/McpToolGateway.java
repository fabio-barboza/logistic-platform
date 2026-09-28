package br.com.fabio.logisticagent.core.gateway;

import java.util.Optional;

public interface McpToolGateway {

    Optional<String> call(String toolName, String argsJson);
}
