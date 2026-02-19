package ensharp_scoring.example.ensharp_scoring.application.service;

import ensharp_scoring.example.ensharp_scoring.application.port.out.DockerPort;
import ensharp_scoring.example.ensharp_scoring.application.port.out.GitPort;
import ensharp_scoring.example.ensharp_scoring.application.port.out.ResultParser;
import ensharp_scoring.example.ensharp_scoring.application.port.out.StoragePort;
import ensharp_scoring.example.ensharp_scoring.domain.model.ContainerResult;
import ensharp_scoring.example.ensharp_scoring.domain.model.SubmissionInfo;
import ensharp_scoring.example.ensharp_scoring.domain.model.TestExecutionResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScoringServiceTest {

        @Mock
        private GitPort gitPort;
        @Mock
        private StoragePort storagePort;
        @Mock
        private DockerPort dockerPort;
        @Mock
        private ResultParser parser;

        @InjectMocks
        private ScoringService scoringService;

        @Captor
        private ArgumentCaptor<Path> pathCaptor;

        @Test
        @DisplayName("시나리오 1: 도커 실행 성공 및 결과 파싱 성공")
        void given_정상적인_제출정보일때_When_채점을_수행하면_Expect_테스트결과를_반환한다() {
                // given
                SubmissionInfo info = SubmissionInfo.builder()
                                .id("uuid")
                                .repoUrl("https://github.com/test/repo.git")
                                .testCaseUrl("https://s3.bucket/test.zip")
                                .build();

                ContainerResult successResult = ContainerResult.builder()
                                .exitCode(0)
                                .logs("Build successful")
                                .build();

                List<TestExecutionResult> expectedResults = List.of(
                                TestExecutionResult.builder().method("test1").passed(true).build());

                // execute(any, any) -> We don't care deeply about exact path/string in setup
                // phase
                given(dockerPort.execute(any(Path.class), any(String.class)))
                                .willReturn(successResult);
                given(parser.parse(any(Path.class)))
                                .willReturn(expectedResults);

                // when
                List<TestExecutionResult> actualResults = scoringService.grade(info);

                // then
                assertThat(actualResults).isEqualTo(expectedResults);

                // 1. 깃 클론 확인
                verify(gitPort).clone(eq(info.repoUrl()), pathCaptor.capture());
                Path capturedPath = pathCaptor.getValue();

                // 2. 테스트 코드 다운로드 확인
                verify(storagePort).downloadTestCode(eq(info.testCaseUrl()), eq(capturedPath));

                // 3. 도커 실행 확인
                verify(dockerPort).execute(eq(capturedPath), eq(info.id()));

                // 4. xml 파서 확인
                verify(parser).parse(eq(capturedPath));
        }

        @Test
        @DisplayName("시나리오 2: 도커 빌드 실패")
        void given_빌드실패하는_코드일때_When_채점을_수행하면_Expect_에러메시지를_반환한다() {
                // given
                SubmissionInfo info = SubmissionInfo.builder()
                                .id("uuid")
                                .repoUrl("https://github.com/test/repo.git")
                                .testCaseUrl("https://s3.bucket/test.zip")
                                .build();

                ContainerResult failureResult = ContainerResult.builder()
                                .exitCode(1)
                                .errorLogs("Build failed")
                                .build();

                given(dockerPort.execute(any(Path.class), any(String.class)))
                                .willReturn(failureResult);

                // when
                List<TestExecutionResult> actualResults = scoringService.grade(info);

                // then
                assertThat(actualResults).hasSize(1);
                assertThat(actualResults.get(0).message()).isEqualTo("Build failed");

                verify(gitPort).clone(eq(info.repoUrl()), any(Path.class));
                verify(dockerPort).execute(any(Path.class), any(String.class));
                verify(parser, never()).parse(any(Path.class));
        }
}
