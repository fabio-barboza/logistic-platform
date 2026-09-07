package br.com.fabio.logisticagent.confirm;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@code details} vira JSON em coluna de texto. {@link LinkedHashMap} na volta porque a ordem das
 * chaves é significativa (ver {@link PendingAction}); o {@code JsonMapper} não reordena.
 */
@Converter
class DetailsJsonConverter implements AttributeConverter<Map<String, String>, String> {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Override
    public String convertToDatabaseColumn(Map<String, String> details) {
        return MAPPER.writeValueAsString(details == null ? Map.of() : details);
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, String>>() {
        });
    }
}
