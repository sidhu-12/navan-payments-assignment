package com.navan.inventory.common;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;

@Configuration
public class JsonConfig {

    @Bean
    Module domainEnumsModule() {
        SimpleModule module = new SimpleModule("domainEnums");
        module.addDeserializer(TransactionType.class, new JsonDeserializer<>() {
            @Override
            public TransactionType deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return TransactionType.fromWire(p.getValueAsString());
            }
        });
        module.addDeserializer(NavanFeeKind.class, new JsonDeserializer<>() {
            @Override
            public NavanFeeKind deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return NavanFeeKind.fromWire(p.getValueAsString());
            }
        });
        return module;
    }
}
