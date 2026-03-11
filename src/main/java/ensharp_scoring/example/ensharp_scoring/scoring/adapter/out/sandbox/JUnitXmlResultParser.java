package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.sandbox;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringStatus;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.TestDetail;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

@Component
public class JUnitXmlResultParser {

    public ScoringResult parse(String submissionId, File xmlFile, int exitCode) {
        // 이 곳에서 DOM 혹은 SAX 파서를 사용하여 JUnit XML을 파싱합니다.
        List<TestDetail> details = new ArrayList<>();
        int totalTests = 0;
        int failedTests = 0;

        // 임시 로직 (파싱 구현 생략)
        boolean hasFailures = failedTests > 0;
        ScoringStatus status;
        
        if (hasFailures) {
            status = ScoringStatus.WA;
        } else if (exitCode != 0) {
            status = ScoringStatus.RE;
        } else {
            status = ScoringStatus.AC;
        }

        return ScoringResult.builder()
                .submissionId(submissionId)
                .overallStatus(status)
                .totalTests(totalTests)
                .passedTests(totalTests - failedTests)
                .details(details)
                .build();
    }
}
