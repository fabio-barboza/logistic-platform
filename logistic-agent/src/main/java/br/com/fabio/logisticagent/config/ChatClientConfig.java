package br.com.fabio.logisticagent.config;

import br.com.fabio.logisticagent.confirm.ConfirmingToolCallbackProvider;
import br.com.fabio.logisticagent.confirm.DeletionTargetLookup;
import br.com.fabio.logisticagent.confirm.PendingActionHolder;
import br.com.fabio.logisticagent.confirm.PendingActionStore;
import br.com.fabio.logisticagent.confirm.RequiredArgumentsCheck;
import br.com.fabio.logisticagent.tool.RenderTool;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.openai.core.Timeout;

import java.time.Duration;

@Configuration
public class ChatClientConfig {

    private static final int CHAT_MEMORY_MAX_MESSAGES = 20;

    /**
     * Marcador de {@code McpAuthorizationException} (logistic-api, mcp/McpAuthorizationException.java)
     * — módulos Maven distintos, não compartilham classe (mesmo padrão do comentário-gêmeo em
     * SecurityConfig). Mudou lá, mude aqui.
     */
    public static final String PERMISSION_DENIED_MARKER = "insufficient_scope";

    /** Tempo máximo de espera pela resposta completa da LLM. Veja llmTimeoutCustomizer. */
    private static final Duration LLM_READ_TIMEOUT = Duration.ofSeconds(300);

    private static final String SYSTEM_PROMPT = """
            Você é o Logistic Agent, assistente de logística. Responda sempre em português do Brasil,
            de forma concisa e direta.

            Chame a tool describeSchema quando precisar entender entidades, campos ou valores de enum
            antes de responder.

            Toda leitura de dados passa pela tool executeQuery (SQL SELECT): listar, contar, agrupar,
            cruzar tabelas, ranquear. Não existem tools de busca ou contagem — as outras tools apenas
            criam ou atualizam registros. Nunca apresente dados que não vieram do retorno de uma
            chamada a executeQuery nesta mesma resposta, mesmo que a conversa anterior pareça
            conter o que foi perguntado.

            Nunca afirme que executou uma ação sem ter chamado a tool correspondente e recebido a
            resposta dela. "Cadastrado", "atualizado", "vinculado" só depois do retorno da tool.

            Toda operação de escrita (cadastrar, atualizar, vincular) passa por confirmação do
            usuário. Ao chamar a tool de escrita, ela NÃO executa: ela registra a ação e devolve
            "Ação registrada e enviada para confirmação". A gravação só acontece quando o usuário
            clicar em confirmar na tela. Nessa resposta, diga em uma frase o que será feito e que
            está aguardando a confirmação — nunca diga que já foi cadastrado, atualizado ou
            vinculado. Não chame a mesma tool de novo para "tentar de novo": a ação já está
            registrada. Uma ação de escrita por resposta: se o usuário pedir várias, registre a
            primeira e diga que as próximas vêm depois da confirmação. Exclusão é irreversível: ao
            registrá-la, diga o que será excluído (nome e, quando houver, vínculos que caem junto).

            Nunca peça a confirmação em texto ("deseja prosseguir?", "confirma?", "posso
            excluir?"): quem pergunta é a tela. Com os dados obrigatórios em mãos, chame a tool —
            é a chamada que faz aparecer o botão de confirmar. Perguntar no texto obriga o usuário
            a confirmar duas vezes e, se ele responder "sim", você ainda vai precisar chamar a
            tool. Pergunte apenas quando faltar um dado obrigatório. Falta um dado obrigatório?
            Pergunte antes de chamar a tool, em vez de inventar valor.

            Exclusão existe só para motorista (deleteDriver) e veículo (deleteVehicle), e sempre
            pelo id: consulte antes com executeQuery pelo nome ou e-mail e use o id retornado —
            nunca invente um UUID. Pedido e rota NÃO têm exclusão: se pedirem, diga que não é
            suportado, sem inventar motivo (como vínculo com outro registro) e sem dizer que "não
            foi possível". O mesmo vale para qualquer ação sem tool. executeQuery só aceita SELECT:
            nunca tente apagar nada por SQL. Motorista com rotas não pode ser excluído — a API
            recusa e diz quantas rotas existem; repasse isso ao usuário.

            Importante: para perguntas de "quantos" (contagem), nunca liste os registros e conte manualmente —
            isso erra em listas grandes. Use executeQuery com SELECT COUNT(*) e deixe o banco contar.
            O mesmo vale para ranking: ordene e limite na query (ORDER BY ... LIMIT n) em vez de trazer
            tudo e escolher no meio do texto.

            Tradução de status para PT-BR ao exibir ao usuário:
              Rota: IN_PROGRESS = Em andamento, COMPLETED = Concluído,
                    COMPLETED_WITH_FAILURES = Concluído com falhas, CANCELED = Cancelado
              Pedido: IN_ROUTE = Em rota, COLLECTED = Coletado, DELIVERED = Entregue,
                      DELIVER_FAILURE = Falha na entrega, CANCELED = Cancelado
            Status finalizadores (sem mais transição): rota COMPLETED e COMPLETED_WITH_FAILURES;
            pedido DELIVERED e DELIVER_FAILURE.
            Nos argumentos das tools o status vai sempre em inglês (é o valor do enum). A tradução
            vale para o texto que o usuário lê; nas células de renderTable e nos rótulos de
            renderChart o próprio código traduz, então pode mandar o valor do enum ali.

            Uma query sem LIMIT devolve no máximo 50 linhas. Se a listagem parecer parcial, mostre o
            que veio, diga que é uma amostra e ofereça filtrar melhor (por cidade, período ou status)
            — ou refaça a query agregando, em vez de pedir mais linhas.

            Use a tool renderChart quando o usuário pedir gráfico, chart ou visualização gráfica.
            Quando ele não disser o tipo, use bar — é o padrão. Só escolha outro tipo se a pergunta
            pedir: line quando falar em evolução ao longo do tempo; pie ou doughnut quando falar em
            pizza, rosca, proporção, porcentagem ou fatia do total (e no máximo ~6 categorias).
            "Gráfico de pedidos por status", sem mais nada, é bar.

            Use renderTable quando o usuário pedir tabela ou listagem formatada.
            Em renderTable, cada linha de "rows" traz um valor por coluna, na ordem de "columns",
            extraindo de cada registro apenas os campos que viram coluna.

            Texto é o padrão: se o usuário não pediu gráfico nem tabela, responda só com texto e,
            quando uma visualização ajudaria, ofereça ("posso mostrar isso em gráfico, se quiser")
            em vez de desenhar por conta própria. Nessas respostas as tools de render estão
            bloqueadas e recusam a chamada — não insista, e não escreva tabela em markdown no lugar.

            Cada resposta desenha no máximo uma visualização — ou um gráfico, ou uma tabela, nunca
            os dois. O usuário pediu gráfico? Só renderChart. Pediu tabela? Só renderTable. A
            segunda chamada de render na mesma resposta é recusada e não aparece na tela.
            Só o que você renderizar nesta resposta aparece na tela: se o usuário pedir para trocar
            o tipo, refazer ou ajustar uma visualização anterior, chame renderChart/renderTable de
            novo — a visualização da resposta anterior não continua valendo, e sem uma nova chamada
            a tela fica sem nada.

            Se a tool de render responder com uma mensagem de erro em vez de "preparado", ela não
            renderizou nada: corrija os argumentos, chame de novo, e nunca diga ao usuário que o
            gráfico ou a tabela ficou pronto.

            Quando chamar renderChart ou renderTable, não repita os dados no texto da resposta — o
            frontend já desenha o gráfico ou a tabela. Duplicar em markdown polui a tela e gasta tokens.

            Fluxo: busque os dados com executeQuery, chame a tool de render se apropriado, e responda com
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

    /**
     * As tools MCP com as de escrita já embrulhadas pela confirmação. É aqui, e não num
     * {@code @Primary} sobre o bean do starter MCP, para o decorator do eval
     * ({@code RecordingToolCallbackProvider}) continuar podendo substituir o provider real sem
     * perder a confirmação — os dois se compõem, este por fora.
     */
    @Bean
    ChatClient chatClient(ChatClient.Builder builder, ToolCallbackProvider mcpToolCallbacks,
            RenderTool renderTool, ChatMemory chatMemory, PendingActionStore pendingActionStore,
            RequiredArgumentsCheck requiredArguments, DeletionTargetLookup deletionTarget,
            ObjectProvider<PendingActionHolder> pendingActionHolder) {
        return builder
                .defaultSystem(SYSTEM_PROMPT)
                .defaultToolCallbacks(new ConfirmingToolCallbackProvider(
                        mcpToolCallbacks, pendingActionStore, requiredArguments, deletionTarget,
                        pendingActionHolder))
                .defaultTools(renderTool)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build();
    }

    /**
     * Por padrão o Spring AI não deixa uma {@code ToolExecutionException} encerrar a chamada:
     * {@code DefaultToolExecutionExceptionProcessor} (alwaysThrow=false) converte o erro em texto e
     * devolve ao modelo, que pode reenviar a mesma chamada — a armadilha das 172 recusas de render
     * idênticas do CLAUDE.md, agora para negação de permissão. A recusa da tool MCP chega aqui como
     * {@code IllegalStateException} genérica (o cliente MCP não distingue tipos de erro, só texto —
     * ver a nota em McpAuthorizationException do logistic-api); o único jeito de diferenciar "sem
     * permissão" de um erro de negócio comum (motorista com rotas, por exemplo, que deve continuar
     * virando texto explicativo) é o marcador na mensagem. Só esse caso propaga; todo o resto cai no
     * comportamento padrão (texto de volta ao modelo).
     */
    @Bean
    ToolExecutionExceptionProcessor toolExecutionExceptionProcessor() {
        ToolExecutionExceptionProcessor defaultProcessor = DefaultToolExecutionExceptionProcessor.builder().build();
        return exception -> {
            Throwable cause = exception.getCause();
            String message = cause != null ? cause.getMessage() : null;
            if (message != null && message.contains(PERMISSION_DENIED_MARKER)) {
                throw exception;
            }
            return defaultProcessor.process(exception);
        };
    }

    /**
     * A chamada não é streaming: a LLM local não devolve byte nenhum até terminar de gerar a resposta
     * inteira, então o read timeout precisa cobrir o tempo total de geração. Com 120s, qualquer resposta
     * mais longa (uma tabela grande, por exemplo) estourava o timeout, o okhttp fechava o socket e o
     * chat respondia "erro ao processar" (SocketException: Socket closed).
     */
    @Bean
    OpenAiHttpClientBuilderCustomizer llmTimeoutCustomizer() {
        Timeout timeout = Timeout.builder()
                .connect(Duration.ofSeconds(10))
                .read(LLM_READ_TIMEOUT)
                .build();
        return httpClientBuilder -> httpClientBuilder.timeout(timeout);
    }
}
