package ensharp_scoring.example.ensharp_scoring.scoring.adapter.in.messaging.dto;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;

public record ScoringRequestMessage(
        String submissionId,
        String repoUrl,
        String testCodeUrl,
        Integer timeLimit,
        Integer memoryLimit
) {
    public ScoringRequest toDomain() {
        return new ScoringRequest(
                submissionId,
                repoUrl,
                testCodeUrl,
                timeLimit != null ? timeLimit.longValue() : 1000L,
                memoryLimit != null ? memoryLimit : 128
        );
    }
}
