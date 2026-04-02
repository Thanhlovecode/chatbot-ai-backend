package dev.thanh.spring_ai.dto.response.admin;

public record TopicCoverageDto(
        String topic,
        int chunkCount,
        int targetChunks,
        double coveragePercent,
        boolean warning
) {
}
