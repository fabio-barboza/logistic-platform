package br.com.fabio.logistic.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Única exceção à regra de acesso via JPA: a consulta é escrita pela LLM em tempo de
 * execução, então não há entidade nem query estática para mapear. Roda sobre o
 * DataSource read-only (role logistic_ro), que não tem permissão de escrita no banco.
 */
@Service
public class QueryService {

    private static final Pattern LIMIT_PATTERN = Pattern.compile("(?i)\\blimit\\b");
    // Teto de linhas devolvidas quando a query não traz LIMIT. Baixo de propósito: o gargalo não é
    // o SQL (responde em <1ms), é a LLM gerar uma linha de tabela por registro no renderTable, e o
    // payload ocupar a janela de contexto. Com as tools tipadas de busca fora, este é o único teto.
    private static final int MAX_ROWS = 50;

    private final JdbcTemplate readOnlyJdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueryService(@Qualifier("readOnlyJdbcTemplate") JdbcTemplate readOnlyJdbcTemplate) {
        this.readOnlyJdbcTemplate = readOnlyJdbcTemplate;
    }

    /**
     * Executa uma consulta SELECT sobre a conexão read-only e devolve o resultado em JSON.
     * A blindagem contra escrita vive no Postgres (role logistic_ro sem GRANT de escrita);
     * as checagens aqui (';', SELECT, LIMIT) são apenas conveniência para o modelo corrigir
     * a query mais rápido, não a defesa em si.
     */
    public String executeQuery(String sql) {
        String trimmed = sql == null ? "" : sql.trim();
        if (trimmed.isEmpty()) {
            return "Erro: a consulta SQL está vazia.";
        }
        if (containsSemicolonOutsideLiterals(trimmed)) {
            return "Erro: a consulta não pode conter ';' — envie apenas um comando SELECT por chamada.";
        }
        String upper = trimmed.toUpperCase();
        if (!(upper.startsWith("SELECT") || upper.startsWith("WITH"))) {
            return "Erro: apenas comandos SELECT (ou WITH ... SELECT) são aceitos.";
        }

        String finalSql = ensureLimit(trimmed);
        try {
            List<Map<String, Object>> rows = readOnlyJdbcTemplate.queryForList(finalSql);
            return objectMapper.writeValueAsString(rows);
        } catch (DataAccessException ex) {
            Throwable cause = ex.getMostSpecificCause();
            return "Erro ao executar a consulta: " + cause.getMessage();
        } catch (JsonProcessingException ex) {
            return "Erro ao montar o resultado da consulta: " + ex.getMessage();
        }
    }

    private boolean containsSemicolonOutsideLiterals(String sql) {
        boolean inLiteral = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'') {
                inLiteral = !inLiteral;
            } else if (c == ';' && !inLiteral) {
                return true;
            }
        }
        return false;
    }

    private String ensureLimit(String sql) {
        if (LIMIT_PATTERN.matcher(sql).find()) {
            return sql;
        }
        return sql + " LIMIT " + MAX_ROWS;
    }
}
