package br.com.fabio.logistic.dto;

import java.util.UUID;

public record DeletionSummary(UUID id, String name, int removedLinks) {
}
