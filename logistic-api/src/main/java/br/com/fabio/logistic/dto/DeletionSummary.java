package br.com.fabio.logistic.dto;

import java.util.UUID;

/**
 * O que a exclusão levou junto. Existe porque `void` não conta essa história: o vínculo
 * motorista↔veículo cai por CASCADE no banco, e uma exclusão que apaga três vínculos em silêncio é
 * exatamente o tipo de efeito que o usuário precisava ver antes de confirmar — e depois, no
 * retorno, para saber o que aconteceu.
 *
 * @param id           id do registro excluído
 * @param name         nome dele, para a frase de retorno não ser só um UUID
 * @param removedLinks vínculos motorista↔veículo removidos junto
 */
public record DeletionSummary(UUID id, String name, int removedLinks) {
}
