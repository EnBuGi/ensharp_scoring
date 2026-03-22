package ensharp_scoring.example.ensharp_scoring.scoring.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class ScoringRequest {
    private final String submissionId;
    private final String repoUrl;
    private final String testCodeUrl;
    private final long timeLimit;
    private final int memoryLimit;
    private final String projectType;
}

