package br.com.fabio.logistic.mcp;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * Tool que descreve o schema do banco para o modelo.
 * <p>
 * O mesmo texto vai na descrição do executeQuery, que está sempre no contexto — esta tool existe
 * para a pergunta explícita sobre o modelo de dados ("quais os status possíveis de uma rota?"),
 * não como pré-requisito da consulta. O texto mora em {@link SchemaText}.
 */
@Component
public class SchemaMcpTools {

    @McpTool(description = """
            Descreve as tabelas, campos, enums e a tradução de status PT-BR do banco de logística.
            Use quando o usuário perguntar sobre o modelo de dados em si — quais status existem,
            que campos uma entidade tem, como as tabelas se relacionam. Para montar uma query, o
            schema já está na descrição do executeQuery.
            """)
    public String describeSchema() {
        return SchemaText.FULL;
    }
}
