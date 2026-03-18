package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.messaging.dto;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import lombok.Builder;
import lombok.Getter;

import java.util.List;
import java.util.stream.Collectors;

@Getter
@Builder
public class ScoringResultMessage {
    private String submissionId;
    private String overallStatus;
    private int totalTests;
    private int passedTests;
    private List<TestDetailMessage> details;

    public static ScoringResultMessage from(ScoringResult domain) {
        return ScoringResultMessage.builder()
                .submissionId(domain.getSubmissionId())
                .overallStatus(domain.getOverallStatus().name())
                .totalTests(domain.getTotalTests())
                .passedTests(domain.getPassedTests())
                .details(domain.getDetails() != null ? 
                        domain.getDetails().stream().map(TestDetailMessage::from).collect(Collectors.toList()) 
                        : null)
                .build();
    }
}
