package ensharp_scoring.example.ensharp_scoring.scoring.application.port.out;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;

public interface ExecuteScoringPort {
    ScoringResult execute(ScoringRequest request);
}
