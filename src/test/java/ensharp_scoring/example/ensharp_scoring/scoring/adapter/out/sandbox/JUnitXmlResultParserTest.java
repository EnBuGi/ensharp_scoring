package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.sandbox;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class JUnitXmlResultParserTest {

    private final JUnitXmlResultParser parser = new JUnitXmlResultParser();

    @Test
    @DisplayName("시나리오: 정상적인 실패 (WA) 결과가 포함된 XML 파싱")
    void XML파싱_실패결과_검증() throws IOException {
        // Given
        File tempXml = File.createTempFile("test-result", ".xml");
        tempXml.deleteOnExit();
        
        String xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<testsuite name=\"com.example.DemoTest\" tests=\"3\" skipped=\"0\" failures=\"1\" errors=\"0\" timestamp=\"2023-10-25T10:00:00\" hostname=\"localhost\" time=\"0.05\">\n" +
                "  <properties/>\n" +
                "  <testcase name=\"testSum\" classname=\"com.example.DemoTest\" time=\"0.01\"/>\n" +
                "  <testcase name=\"testDiff\" classname=\"com.example.DemoTest\" time=\"0.02\">\n" +
                "    <failure message=\"expected: &lt;5&gt; but was: &lt;4&gt;\" type=\"org.opentest4j.AssertionFailedError\">Failure details...</failure>\n" +
                "  </testcase>\n" +
                "  <testcase name=\"testMult\" classname=\"com.example.DemoTest\" time=\"0.01\"/>\n" +
                "  <system-out><![CDATA[]]></system-out>\n" +
                "  <system-err><![CDATA[]]></system-err>\n" +
                "</testsuite>";
        
        try (FileWriter writer = new FileWriter(tempXml)) {
            writer.write(xmlContent);
        }

        // When
        ScoringResult result = parser.parse("sub-123", java.util.List.of(tempXml), 1, Collections.emptyList());

        // Then
        assertThat(result.getSubmissionId()).isEqualTo("sub-123");
        assertThat(result.getOverallStatus()).isEqualTo(ScoringStatus.WRONG_ANSWER);
        assertThat(result.getTotalTests()).isEqualTo(3);
        assertThat(result.getPassedTests()).isEqualTo(2);
        assertThat(result.getDetails()).hasSize(3);
        assertThat(result.getDetails().get(1).getStatus()).isEqualTo("FAILED");
    }
    
    @Test
    @DisplayName("시나리오: 모든 테스트 통과 (AC) XML 파싱")
    void XML파싱_성공결과_검증() throws IOException {
        // Given
        File tempXml = File.createTempFile("test-result-success", ".xml");
        tempXml.deleteOnExit();
        
        String xmlContent = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<testsuite name=\"com.example.DemoTest\" tests=\"2\" skipped=\"0\" failures=\"0\" errors=\"0\" timestamp=\"2023-10-25T10:00:00\" hostname=\"localhost\" time=\"0.02\">\n" +
                "  <properties/>\n" +
                "  <testcase name=\"testSum\" classname=\"com.example.DemoTest\" time=\"0.01\"/>\n" +
                "  <testcase name=\"testDiff\" classname=\"com.example.DemoTest\" time=\"0.01\"/>\n" +
                "  <system-out><![CDATA[]]></system-out>\n" +
                "  <system-err><![CDATA[]]></system-err>\n" +
                "</testsuite>";
        
        try (FileWriter writer = new FileWriter(tempXml)) {
            writer.write(xmlContent);
        }

        // When
        ScoringResult result = parser.parse("sub-456", java.util.List.of(tempXml), 0, Collections.emptyList());

        // Then
        assertThat(result.getSubmissionId()).isEqualTo("sub-456");
        assertThat(result.getOverallStatus()).isEqualTo(ScoringStatus.ACCEPTED);
        assertThat(result.getTotalTests()).isEqualTo(2);
        assertThat(result.getPassedTests()).isEqualTo(2);
        assertThat(result.getDetails()).hasSize(2);
        assertThat(result.getDetails().get(0).getStatus()).isEqualTo("PASSED");
    }
}
