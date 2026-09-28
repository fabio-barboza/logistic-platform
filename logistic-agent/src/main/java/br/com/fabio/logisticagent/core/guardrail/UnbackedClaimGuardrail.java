package br.com.fabio.logisticagent.core.guardrail;

import java.util.List;
import java.util.regex.Pattern;

public final class UnbackedClaimGuardrail {

    private static final Pattern AFFIRMATIVE = Pattern.compile(
            "^\\W*(sim|s|claro|ok|okay|isso|pode|podes|quero|manda|mandar|bora|beleza|blz|vai|"
                    + "aceito|mostra|mostre|faz|faça|por favor|pf)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WRITE_REQUEST = Pattern.compile(
            "cadastr|adicion|crie\\b|criar\\b|exclu|apag|delet|remov|atualiz|alter|edit|"
                    + "vincul|desvincul|atribu|associ",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern DENIAL = Pattern.compile(
            "n[ãa]o\\s+(é|s[ãa]o|est[áa]|foi|foram)\\s+(poss[íi]vel|suportad\\w*|permitid\\w*|"
                    + "realizad\\w*|efetuad\\w*|gravad\\w*|cadastrad\\w*|exclu[íi]d\\w*|dispon[íi]ve\\w*)|"
                    + "n[ãa]o\\s+(posso|consigo|consegui|suport\\w*|tenho\\s+permiss\\w*)|"
                    + "n[ãa]o\\s+h[áa]\\s+(\\w+\\s+){0,2}(suporte|ferramenta|tool|como|permiss\\w*)|"
                    + "sem\\s+permiss\\w*|n[ãa]o\\s+\\w+\\s+(permiss[ãa]o|autoriza\\w*)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern VISUAL_CLAIM = Pattern.compile(
            "gr[áa]fico|chart|tabela|pizza|rosca|donut|doughnut", Pattern.CASE_INSENSITIVE);

    public static final List<String> RENDER_CORRECTIONS = List.of("""
            Sua resposta anterior anunciou um gráfico ou uma tabela, mas você não chamou renderChart
            nem renderTable — a tela do usuário ficou vazia. Refaça agora: se ainda não consultou os
            dados nesta resposta, chame executeQuery, e então chame a tool de render informando as
            colunas do resultado. Depois responda com um texto curto. Se não for caso de desenhar
            nada, responda sem prometer gráfico nem tabela.
            """, """
            Você continua sem chamar a tool de render e a tela segue vazia. Listar os dados em texto
            não desenha nada. Nesta resposta faça exatamente isto: chame renderChart (gráfico) ou
            renderTable (tabela) com as colunas do resultado da consulta, e escreva no máximo uma
            frase depois. Se não houver dados para desenhar, diga isso e não prometa visualização.
            """);

    private static final Pattern ACTION_CLAIM = Pattern.compile(
            "aguard\\w*\\s+(a\\s+|sua\\s+)?confirma|"
                    + "a[çc][ãa]o\\s+(foi\\s+)?registrada|"
                    + "ser[áa]\\s+(realizada|executada|efetivada|registrada)|"
                    + "clique\\s+em\\s+confirmar|confirme\\s+(a\\s+a[çc][ãa]o|abaixo|para\\s+executar)|"
                    + "ser[áa]\\s+\\w+d[oa]s?\\b|assim\\s+que\\s+voc[êe]\\s+confirmar|"
                    + "confirmar\\s+(na\\s+tela|no\\s+card|abaixo)|"
                    + "(cadastrad|criad|exclu[íi]d|removid|atualizad|alterad|apagad|deletad|vinculad|"
                    + "atribu[íi]d|registrad|conclu[íi]d|efetuad)\\w*\\s+com\\s+sucesso|"
                    + "\\b(cadastrei|criei|exclu[íi]|removi|atualizei|alterei|apaguei|deletei|"
                    + "vinculei|atribu[íi]|registrei)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);

    public static final List<String> ACTION_CORRECTIONS = List.of("""
            Sua resposta anterior disse que a ação está registrada, aguardando confirmação ou já
            concluída, mas nenhuma escrita foi registrada neste turno — nada foi gravado e o usuário
            não recebeu botão nenhum na tela. Escrita não acontece por executeQuery: ela só existe
            pelas tools de escrita, e mesmo elas apenas registram a ação para o usuário confirmar.
            Refaça agora: chame a tool de escrita correspondente
            (createDriver, createVehicle, createOrder, createRoute, updateOrderStatus,
            updateRouteStatus, linkDriverVehicle ou assignOrderToRoute) com os dados que o usuário
            já forneceu nesta conversa. Se ainda faltar algum dado obrigatório, pergunte por ele em
            vez de anunciar a ação.
            """, """
            Você continua anunciando a ação sem chamar a tool de escrita, e a tela do usuário segue
            sem o botão de confirmar. Escrever a intenção em texto não registra nada, e afirmar que
            já foi feito é pior: o usuário acredita numa gravação que não aconteceu. Nesta resposta,
            chame a tool de escrita com os dados desta conversa e escreva no máximo uma frase depois
            disso. Se a tool não estiver disponível para você, diga claramente que a operação NÃO
            foi realizada e que você não consegue executá-la — sem prometer e sem dar por feita.
            """);

    private static final Pattern DATA_CLAIM = Pattern.compile("\\d");

    public static final List<String> DATA_CORRECTIONS = List.of("""
            Sua resposta anterior apresentou dados (números, listagem ou tabela) sem que você tenha
            chamado a tool executeQuery neste turno. Esses dados não vieram do banco. Refaça agora:
            chame executeQuery com o SQL que responde exatamente à pergunta — inclusive os filtros
            que já valiam na pergunta anterior desta conversa — e responda só com o que a tool
            devolver.
            """, """
            Você respondeu de novo sem chamar executeQuery. Nada do que está na conversa anterior
            serve como fonte: os números precisam vir de uma consulta feita AGORA. Nesta resposta,
            chame executeQuery antes de escrever qualquer número. Se por algum motivo não conseguir
            montar a consulta, diga isso ao usuário e não apresente dado nenhum.
            """);

    private UnbackedClaimGuardrail() {
    }

    public static boolean isWriteRequest(String message) {
        return WRITE_REQUEST.matcher(nullToEmpty(message)).find();
    }

    public static boolean isAffirmative(String message) {
        return AFFIRMATIVE.matcher(nullToEmpty(message)).find();
    }

    public static boolean isDenial(String text) {
        return DENIAL.matcher(nullToEmpty(text)).find();
    }

    public static boolean claimsVisual(String content) {
        return VISUAL_CLAIM.matcher(nullToEmpty(content)).find();
    }

    public static boolean claimsAction(String content) {
        return ACTION_CLAIM.matcher(nullToEmpty(content)).find();
    }

    public static boolean claimsData(String content) {
        return DATA_CLAIM.matcher(nullToEmpty(content)).find();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
