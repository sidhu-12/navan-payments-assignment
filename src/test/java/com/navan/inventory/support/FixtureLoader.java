package com.navan.inventory.support;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.Map;

public final class FixtureLoader {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private FixtureLoader() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> loadSeed(String key) {
        try {
            Map<String, Object> payload = OBJECT_MAPPER.readValue(
                    new ClassPathResource("fixtures/task-b/seed-payloads.json").getInputStream(),
                    new TypeReference<>() {}
            );
            return (Map<String, Object>) payload.get(key);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load fixture " + key, e);
        }
    }
}
