package ensharp_scoring.example.ensharp_scoring.domain.model;

import lombok.Builder;

@Builder
public record SubmissionInfo(
        String id,
        String repoUrl,
        String testCaseUrl) {

    public SubmissionInfo {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Submission ID cannot be empty");
        }
        if (repoUrl == null || repoUrl.isBlank()) {
            throw new IllegalArgumentException("Repository URL cannot be empty");
        }
        if (testCaseUrl == null || testCaseUrl.isBlank()) {
            throw new IllegalArgumentException("Test Case URL cannot be empty");
        }
    }
}
