package ensharp_scoring.example.ensharp_scoring.scoring.domain;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ScoringRequest {
    private final String submissionId;
    private final String repoUrl;
    private final String testCaseUrl;
    private final long timeLimitMs;
    private final int memoryLimitMb;
}
