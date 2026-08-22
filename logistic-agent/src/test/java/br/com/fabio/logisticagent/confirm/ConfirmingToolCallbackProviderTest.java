package br.com.fabio.logisticagent.confirm;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConfirmingToolCallbackProviderTest {

    private final List<String> executed = new ArrayList<>();

    private PendingActionStore store;
    private PendingActionHolder holder;
    private RequiredArgumentsCheck requiredArguments;
    private DeletionTargetLookup deletionTarget;
    private ConfirmingToolCallbackProvider provider;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new PendingActionStore();
        JsonMapper jsonMapper = JsonMapper.builder().build();
        PendingActionMapper labels = new PendingActionMapper(jsonMapper);
        requiredArguments = new RequiredArgumentsCheck(jsonMapper, labels);
        deletionTarget = new DeletionTargetLookup(jsonMapper, labels);
        holder = new PendingActionHolder();
        holder.setSessionId("sessao-1");
        ObjectProvider<PendingActionHolder> holderProvider = mock(ObjectProvider.class);
        when(holderProvider.getIfAvailable()).thenReturn(holder);
        provider = new ConfirmingToolCallbackProvider(
                () -> new ToolCallback[] {
                        fake("createDriver"), fake("executeQuery"), fake("updateOrderStatus")},
                store, requiredArguments, deletionTarget, holderProvider);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private ToolCallback callback(String name) {
        return java.util.Arrays.stream(provider.getToolCallbacks())
                .filter(tool -> tool.getToolDefinition().name().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private void authenticateWithRoles(String... roles) {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user-x")
                .claim("realm_access", Map.of("roles", List.of(roles)))
                .build();
        List<GrantedAuthority> authorities = java.util.Arrays.stream(roles)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, authorities));
    }

    /**
     * Sem a role write, deleteDriver/createDriver nem aparecem para o modelo escolher — é o que
     * evita o card de confirmação seguido de 403 no clique (ver javadoc da classe).
     */
    @Test
    void writeToolsAreHiddenFromUserWithoutWriteRole() {
        authenticateWithRoles("chat", "read");

        List<String> names = java.util.Arrays.stream(provider.getToolCallbacks())
                .map(tool -> tool.getToolDefinition().name()).toList();

        assertThat(names).containsExactly("executeQuery");
    }

    @Test
    void allToolsVisibleForUserWithWriteRole() {
        authenticateWithRoles("chat", "read", "write");

        List<String> names = java.util.Arrays.stream(provider.getToolCallbacks())
                .map(tool -> tool.getToolDefinition().name()).toList();

        assertThat(names).containsExactlyInAnyOrder("createDriver", "executeQuery", "updateOrderStatus");
    }

    /** Sem role read nenhuma, nem a leitura aparece. */
    @Test
    void readToolIsHiddenFromUserWithoutReadRole() {
        authenticateWithRoles("chat");

        List<String> names = java.util.Arrays.stream(provider.getToolCallbacks())
                .map(tool -> tool.getToolDefinition().name()).toList();

        assertThat(names).isEmpty();
    }

    /** Sem usuário autenticado (handshake MCP do startup), a lista não é filtrada. */
    @Test
    void nothingIsHiddenWithoutAuthentication() {
        List<String> names = java.util.Arrays.stream(provider.getToolCallbacks())
                .map(tool -> tool.getToolDefinition().name()).toList();

        assertThat(names).containsExactlyInAnyOrder("createDriver", "executeQuery", "updateOrderStatus");
    }

    @Test
    void writeToolRegistersInsteadOfExecuting() {
        String result = callback("createDriver").call("{\"name\":\"João\"}");

        assertThat(executed).isEmpty();
        assertThat(result).contains("confirmação do usuário").contains("NADA foi gravado");
        assertThat(holder.get()).isNotNull();
        assertThat(holder.get().toolName()).isEqualTo("createDriver");
        assertThat(holder.get().argsJson()).isEqualTo("{\"name\":\"João\"}");
        assertThat(holder.get().sessionId()).isEqualTo("sessao-1");
    }

    @Test
    void readToolIsNotWrapped() {
        String result = callback("executeQuery").call("{\"sql\":\"SELECT 1\"}");

        assertThat(executed).containsExactly("executeQuery");
        assertThat(result).isEqualTo("ok:executeQuery");
        assertThat(holder.get()).isNull();
    }

    /**
     * Repetir a mesma chamada devolve a mesma pendência, sem registrar outra. É o retorno de
     * sucesso que encerra o loop de tool calls — recusar de novo faria o modelo determinístico
     * reenviar para sempre, como já aconteceu com o render.
     */
    @Test
    void repeatedIdenticalCallReturnsSamePendingAction() {
        callback("createDriver").call("{\"name\":\"João\"}");
        String id = holder.get().id();

        String result = callback("createDriver").call("{\"name\":\"João\"}");

        assertThat(holder.get().id()).isEqualTo(id);
        assertThat(store.size()).isEqualTo(1);
        assertThat(result).contains("NADA foi gravado");
        assertThat(executed).isEmpty();
    }

    @Test
    void secondDistinctWriteIsRejectedAndNeverExecutes() {
        callback("createDriver").call("{\"name\":\"João\"}");

        String first = callback("updateOrderStatus").call("{\"status\":\"DELIVERED\"}");
        String second = callback("updateOrderStatus").call("{\"status\":\"DELIVERED\"}");

        assertThat(first).contains("Já existe uma ação aguardando confirmação");
        assertThat(second).contains("Não chame mais nenhuma tool de escrita");
        assertThat(executed).isEmpty();
        assertThat(holder.get().toolName()).isEqualTo("createDriver");
        assertThat(store.size()).isEqualTo(1);
    }

    /** Fora de requisição (eval, sem servlet) não há quem confirme: a tool executa como antes. */
    @Test
    @SuppressWarnings("unchecked")
    void writeExecutesWhenThereIsNoRequestScopedHolder() {
        ObjectProvider<PendingActionHolder> noHolder = mock(ObjectProvider.class);
        when(noHolder.getIfAvailable()).thenThrow(new IllegalStateException("No thread-bound request"));
        ConfirmingToolCallbackProvider outsideRequest = new ConfirmingToolCallbackProvider(
                () -> new ToolCallback[] {fake("createDriver")}, store, requiredArguments, deletionTarget, noHolder);

        String result = outsideRequest.getToolCallbacks()[0].call("{}");

        assertThat(result).isEqualTo("ok:createDriver");
        assertThat(executed).containsExactly("createDriver");
    }

    /**
     * Campo obrigatório faltando não vira pendência: o card mostraria um dado que ninguém
     * informou. A tool manda o modelo perguntar, com o mesmo rótulo que a tela usa depois.
     */
    @Test
    void incompleteWriteAsksTheUserInsteadOfRegistering() {
        ToolCallback createVehicle = withSchema();

        String result = createVehicle.call("{\"name\":\"Truck X\"}");

        assertThat(result).contains("Faltam dados obrigatórios").contains("Capacidade (kg)");
        assertThat(result).doesNotContain("Nome");
        assertThat(holder.get()).isNull();
        assertThat(store.size()).isZero();
        assertThat(executed).isEmpty();
    }

    /** "N/A" e companhia são o modelo dizendo que não sabe — e iriam para o banco como texto. */
    @Test
    void placeholderValueCountsAsMissing() {
        String result = withSchema().call("{\"name\":\"N/A\",\"capacityKg\":180}");

        assertThat(result).contains("Faltam dados obrigatórios").contains("Nome");
        assertThat(holder.get()).isNull();
    }

    @Test
    void completeWriteRegistersNormally() {
        String result = withSchema().call("{\"name\":\"Truck X\",\"capacityKg\":180}");

        assertThat(result).contains("NADA foi gravado");
        assertThat(holder.get()).isNotNull();
    }

    /** Insistir na chamada incompleta esbarra no teto e o modelo é mandado parar de chamar. */
    @Test
    void repeatedIncompleteCallStopsAskingAndTellsTheModelToStop() {
        ToolCallback createVehicle = withSchema();
        createVehicle.call("{\"name\":\"Truck X\"}");

        String result = createVehicle.call("{\"name\":\"Truck X\"}");

        assertThat(result).contains("Não chame mais esta tool");
        assertThat(store.size()).isZero();
    }

    private ToolCallback withSchema() {
        ConfirmingToolCallbackProvider withSchema = new ConfirmingToolCallbackProvider(
                () -> new ToolCallback[] {fake("createVehicle", """
                        {"type":"object",
                         "properties":{"name":{"type":"string"},"capacityKg":{"type":"integer"}},
                         "required":["name","capacityKg"]}
                        """)},
                store, requiredArguments, deletionTarget, holderProvider());
        return withSchema.getToolCallbacks()[0];
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<PendingActionHolder> holderProvider() {
        ObjectProvider<PendingActionHolder> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(holder);
        return provider;
    }

    private ToolCallback fake(String name) {
        return fake(name, "{}");
    }

    private ToolCallback fake(String name, String inputSchema) {
        return new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder().name(name).description(name)
                        .inputSchema(inputSchema).build();
            }

            @Override
            public String call(String toolInput) {
                executed.add(name);
                return "ok:" + name;
            }
        };
    }

    /**
     * Exclusão: o agent consulta o registro alvo antes de registrar a pendência, para o card
     * mostrar o motorista em vez de um UUID.
     */
    @Test
    void deletionLooksUpTheTargetAndKeepsItsFields() {
        ToolCallback deleteDriver = deleteTool("[{\"text\":\"[{\\\"email\\\":\\\"joao@x.com\\\","
                + "\\\"name\\\":\\\"João Ribeiro\\\"}]\"}]");

        String result = deleteDriver.call("{\"id\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"}");

        assertThat(result).contains("NADA foi gravado");
        // ordem declarada no lookup: o JSON da consulta não preserva a ordem das colunas
        assertThat(holder.get().details())
                .containsExactly(entry("Nome", "João Ribeiro"), entry("E-mail", "joao@x.com"));
    }

    /** Id inventado: nada é registrado, e o modelo é mandado consultar antes. */
    @Test
    void deletionOfUnknownIdIsRefusedBeforeShowingACard() {
        ToolCallback deleteDriver = deleteTool("[{\"text\":\"[]\"}]");

        String result = deleteDriver.call("{\"id\":\"3fa85f64-5717-4562-b3fc-2c963f66afa6\"}");

        assertThat(result).contains("Nenhum motorista com esse id").contains("executeQuery");
        assertThat(holder.get()).isNull();
        assertThat(store.size()).isZero();
        // só a consulta do alvo rodou; a exclusão não
        assertThat(executed).containsExactly("executeQuery");
    }

    @Test
    void deletionWithMalformedIdIsRefused() {
        assertThat(deleteTool("[{\"text\":\"[]\"}]").call("{\"id\":\"o do João\"}"))
                .contains("Nenhum motorista com esse id");
        assertThat(holder.get()).isNull();
    }

    /** deleteDriver + um executeQuery que devolve o que o teste mandar. */
    private ToolCallback deleteTool(String queryResult) {
        ToolCallback query = new ToolCallback() {

            @Override
            public ToolDefinition getToolDefinition() {
                return DefaultToolDefinition.builder()
                        .name("executeQuery").description("executeQuery").inputSchema("{}").build();
            }

            @Override
            public String call(String toolInput) {
                executed.add("executeQuery");
                return queryResult;
            }
        };
        ConfirmingToolCallbackProvider withDelete = new ConfirmingToolCallbackProvider(
                () -> new ToolCallback[] {fake("deleteDriver", """
                        {"type":"object","properties":{"id":{"type":"string"}},"required":["id"]}
                        """), query},
                store, requiredArguments, deletionTarget, holderProvider());
        return withDelete.getToolCallbacks()[0];
    }
}
