package ensharp_scoring.example.ensharp_scoring.scoring.domain;

import lombok.Builder;
import lombok.Getter;
import java.util.List;

@Getter
@Builder
public class ScoringResult {
    private final String submissionId;
    private final ScoringStatus overallStatus;
    private final int totalTests;
    private final int passedTests;
    private final List<TestDetail> details;
    private final String buildLog;
}
