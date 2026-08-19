package br.com.fabio.logistic.mcp;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

/**
 * Monta o Pageable das tools de busca: limit default 25, teto 100.
 * O teto é o que garante o tamanho do payload — pedir ao modelo, via prompt, que não peça mais
 * é conselho, não limite. Payload grande estoura a janela de contexto e faz o modelo gerar uma
 * linha de render por registro, que é o gargalo real de latência.
 */
final class McpPageSupport {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private McpPageSupport() {
    }

    static Pageable of(Integer limit) {
        int size = (limit == null || limit <= 0) ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return PageRequest.of(0, size);
    }
}
