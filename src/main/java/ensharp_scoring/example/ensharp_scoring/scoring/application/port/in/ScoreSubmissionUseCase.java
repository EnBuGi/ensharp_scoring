package ensharp_scoring.example.ensharp_scoring.scoring.application.port.in;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;

public interface ScoreSubmissionUseCase {
    void score(ScoringRequest request);
}
