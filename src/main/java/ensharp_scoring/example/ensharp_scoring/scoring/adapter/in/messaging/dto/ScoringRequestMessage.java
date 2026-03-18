package ensharp_scoring.example.ensharp_scoring.scoring.adapter.in.messaging.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;

@Getter
@NoArgsConstructor
public class ScoringRequestMessage {
    private String submissionId;
    private String repoUrl;
    private String testCaseUrl;
    private long timeLimitMs;
    private int memoryLimitMb;

    public ScoringRequest toDomain() {
        return ScoringRequest.builder()
                .submissionId(submissionId)
                .repoUrl(repoUrl)
                .testCaseUrl(testCaseUrl)
                .timeLimitMs(timeLimitMs)
                .memoryLimitMb(memoryLimitMb)
                .build();
    }
}
