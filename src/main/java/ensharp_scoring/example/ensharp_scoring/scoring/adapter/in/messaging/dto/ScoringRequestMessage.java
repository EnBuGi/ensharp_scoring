package ensharp_scoring.example.ensharp_scoring.scoring.adapter.in.messaging.dto;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.TestCaseDto;

import java.util.List;
import java.util.UUID;

public record ScoringRequestMessage(
        String submissionId,
        UUID userId,
        UUID projectId,
        String repoUrl,
        String testCodeUrl,
        Integer timeLimit,
        Integer memoryLimit,
        String projectType,
        List<TestCaseDto> testCases,
        String githubAccessToken
) {
    public ScoringRequest toDomain() {
        return new ScoringRequest(
                submissionId,
                repoUrl,
                testCodeUrl,
                timeLimit != null ? timeLimit.longValue() : 1000L,
                memoryLimit != null ? memoryLimit : 128,
                projectType,
                testCases,
                githubAccessToken
        );
    }

    @Override
    public String toString() {
        return "ScoringRequestMessage[" +
                "submissionId=" + submissionId +
                ", userId=" + userId +
                ", projectId=" + projectId +
                ", repoUrl=" + repoUrl +
                ", testCodeUrl=" + testCodeUrl +
                ", timeLimit=" + timeLimit +
                ", memoryLimit=" + memoryLimit +
                ", projectType=" + projectType +
                ", testCases=" + testCases +
                ", githubAccessToken=[PROTECTED]" +
                "]";
    }
}
