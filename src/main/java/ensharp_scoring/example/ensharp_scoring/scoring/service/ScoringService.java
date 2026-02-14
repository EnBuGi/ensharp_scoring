package ensharp_scoring.example.ensharp_scoring.scoring.service;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringInfo;
import lombok.RequiredArgsConstructor;
import ensharp_scoring.example.ensharp_scoring.scoring.event.EventPublisher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringService {

    private final GitRepositoryManager gitRepositoryManager;
    private final BuildRunner buildRunner;
    private final TestResultParser testResultParser;
    private final EventPublisher eventPublisher;

    public void score(ScoringInfo info) {
        log.info("Starting scoring for: {}", info);

        try {
            java.io.File repositoryDir = gitRepositoryManager.cloneOrPull(info.repositoryUrl());
            buildRunner.runTests(repositoryDir);
            java.util.List<String> results = testResultParser.parse(repositoryDir);
            eventPublisher.publishResult(info.toString(), results);
        } catch (Exception e) {
            log.error("Scoring failed", e);
            eventPublisher.publishError(info.toString(), e.getMessage());
        }
    }
}
