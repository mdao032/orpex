package com.orpex.rule;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Stocke un dictionnaire texte → texte en JSON dans une colonne TEXT. */
@Converter
class StringMapJsonConverter implements AttributeConverter<Map<String, String>, String> {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, String>> TYPE = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(Map<String, String> values) {
        return JSON.writeValueAsString(values == null ? Map.of() : values);
    }

    @Override
    public Map<String, String> convertToEntityAttribute(String json) {
        return json == null || json.isBlank() ? new LinkedHashMap<>() : JSON.readValue(json, TYPE);
    }
}
