package ensharp_scoring.example.ensharp_scoring.scoring.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.util.List;

@Getter
@Builder
@AllArgsConstructor
@ToString
public class ScoringRequest {
    private final String submissionId;
    private final String repoUrl;
    private final String testCodeUrl;
    private final long timeLimit;
    private final int memoryLimit;
    private final String projectType;
    private final List<TestCaseDto> testCases;
    @ToString.Exclude
    private final String githubAccessToken;
}

