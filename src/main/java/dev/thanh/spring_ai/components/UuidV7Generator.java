package dev.thanh.spring_ai.components;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.github.f4b6a3.uuid.UuidCreator;

@Component
public class UuidV7Generator {

    public UUID generate() {
        return UuidCreator.getTimeOrderedEpoch();
    }
}