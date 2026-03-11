package ensharp_scoring.example.ensharp_scoring.scoring.application;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.in.ScoreSubmissionUseCase;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.ExecuteScoringPort;
import ensharp_scoring.example.ensharp_scoring.scoring.application.port.out.PublishScoringResultPort;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringRequest;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;

@Service
@RequiredArgsConstructor
public class ScoringService implements ScoreSubmissionUseCase {

    private final ExecuteScoringPort executeScoringPort;
    private final PublishScoringResultPort publishScoringResultPort;

    @Override
    public void score(ScoringRequest request) {
        ScoringResult result = executeScoringPort.execute(request);
        publishScoringResultPort.publish(result);
    }

}
