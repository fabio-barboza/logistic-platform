package br.com.fabio.logisticagent.config;

import br.com.fabio.logisticagent.tool.RenderTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openai.core.Timeout;

import java.time.Duration;

@Configuration
public class ChatClientConfig {

    private static final int CHAT_MEMORY_MAX_MESSAGES = 20;

    private static final String SYSTEM_PROMPT = """
            Você é o Logistic Agent, assistente de logística. Responda sempre em português do Brasil,
            de forma concisa e direta.

            Chame a tool describe_schema quando precisar entender entidades, campos ou valores de enum
            antes de responder.

            Ordem de preferência entre as tools: use primeiro as tools tipadas (searchDrivers, searchVehicles,
            searchRoutes, searchOrders, countOrdersBy, countRoutesBy, getDriver, getVehicle, getRoute, getOrder
            e as demais tools específicas). Só use execute_query (SQL SELECT) quando a pergunta exigir join
            entre entidades, agregação ou recorte fora do catálogo dessas tools. Nunca use execute_query para
            o que uma tool tipada já responde.

            Importante: para perguntas de "quantos" (contagem), nunca liste os registros e conte manualmente —
            isso erra em listas grandes. Use countOrdersBy/countRoutesBy quando o agrupamento pedido for
            suportado por elas; para contar motoristas, veículos, ou qualquer contagem fora do que essas tools
            cobrem, use execute_query com SELECT COUNT(*).

            Tradução de status para PT-BR ao exibir ao usuário:
              Rota: IN_PROGRESS = Em andamento, COMPLETED = Concluído,
                    COMPLETED_WITH_FAILURES = Concluído com falhas, CANCELED = Cancelado
              Pedido: IN_ROUTE = Em rota, COLLECTED = Coletado, DELIVERED = Entregue,
                      DELIVER_FAILURE = Falha na entrega, CANCELED = Cancelado
            Status finalizadores (sem mais transição): rota COMPLETED e COMPLETED_WITH_FAILURES;
            pedido DELIVERED e DELIVER_FAILURE.

            Use a tool renderChart quando o usuário pedir gráfico, chart ou visualização gráfica:
              bar para comparação entre categorias, line para evolução ao longo do tempo,
              pie ou doughnut para proporção de um todo (até ~6 categorias).
            Use renderTable quando o usuário pedir tabela ou listagem formatada, ou quando os dados
            tabulares forem mais claros que texto corrido. Caso contrário, responda só com texto.
            Em renderTable, cada linha de "rows" traz um valor por coluna, na ordem de "columns",
            extraindo de cada registro apenas os campos que viram coluna.

            Fluxo: busque os dados via tools MCP, chame a tool de render se apropriado, e responda com
            um texto curto confirmando o que foi feito (ex.: "Aqui está o gráfico de entregas por estado.").

            Nunca invente dados. Se a tool voltar vazia, diga que não há registros.
            """;

    @Bean
    ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(CHAT_MEMORY_MAX_MESSAGES)
                .build();
    }

    @Bean
    ChatClient chatClient(ChatClient.Builder builder, ToolCallbackProvider mcpToolCallbacks,
            RenderTool renderTool, ChatMemory chatMemory) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultToolCallbacks(mcpToolCallbacks)
                .defaultTools(renderTool)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    @Bean
    OpenAiHttpClientBuilderCustomizer llmTimeoutCustomizer() {
        Timeout timeout = Timeout.builder()
                .connect(Duration.ofSeconds(10))
                .read(Duration.ofSeconds(120))
                .build();
        return httpClientBuilder -> httpClientBuilder.timeout(timeout);
    }
}
