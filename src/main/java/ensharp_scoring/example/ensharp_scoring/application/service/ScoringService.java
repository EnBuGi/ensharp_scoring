package ensharp_scoring.example.ensharp_scoring.application.service;

import ensharp_scoring.example.ensharp_scoring.application.port.out.DockerPort;
import ensharp_scoring.example.ensharp_scoring.application.port.out.GitPort;
import ensharp_scoring.example.ensharp_scoring.application.port.out.ResultParser;
import ensharp_scoring.example.ensharp_scoring.application.port.out.StoragePort;
import ensharp_scoring.example.ensharp_scoring.domain.model.SubmissionInfo;
import ensharp_scoring.example.ensharp_scoring.domain.model.TestExecutionResult;
import ensharp_scoring.example.ensharp_scoring.domain.model.ContainerResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScoringService {
    private final GitPort gitPort;
    private final StoragePort storagePort;
    private final DockerPort dockerPort;
    private final ResultParser parser;

    public List<TestExecutionResult> grade(SubmissionInfo info) {
        Path tempDir = null;
        try {
            // 0. 임시 디렉토리 생성
            tempDir = Files.createTempDirectory("grading");

            // 1. 소스 코드 가져오기
            gitPort.clone(info.repoUrl(), tempDir);

            // 2. 테스트 코드 주입 (덮어쓰기)
            storagePort.downloadTestCode(info.testCaseUrl(), tempDir);

            // 3. 도커 실행 (보안 옵션은 Adapter 내부 구현)
            // ID만 전달하여 컨테이너 이름 생성 로직을 포트 구현체에게 위임 (캡슐화)
            ContainerResult result = dockerPort.execute(tempDir, info.id());

            // 4. 빌드 실패 체크
            if (result.exitCode() != 0) {
                return List.of(TestExecutionResult.buildError(result.errorLogs()));
            }

            // 5. 결과 파싱 (XML -> VO)
            return parser.parse(tempDir);

        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp directory", e);
        } finally {
            // 6. 정리 (필수)
            if (tempDir != null) {
                try {
                    FileSystemUtils.deleteRecursively(tempDir);
                } catch (IOException e) {
                    // 로그만 남기고 무시
                    System.err.println("Failed to delete temp directory: " + e.getMessage());
                }
            }
        }
    }
}
