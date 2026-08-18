package br.com.fabio.logistic.mcp;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/** Monta o Pageable das tools de busca: limit default 100, máximo 500 — payload grande estoura a janela de contexto do modelo. */
final class McpPageSupport {

    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;

    private McpPageSupport() {
    }

    static Pageable of(Integer limit) {
        int size = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return PageRequest.of(0, size);
    }
}
