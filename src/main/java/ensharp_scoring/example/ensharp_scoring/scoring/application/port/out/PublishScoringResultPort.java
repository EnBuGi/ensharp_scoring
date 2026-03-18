package ensharp_scoring.example.ensharp_scoring.scoring.application.port.out;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;

public interface PublishScoringResultPort {
    void publish(ScoringResult result);
}
