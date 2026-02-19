package ensharp_scoring.example.ensharp_scoring.infra.parser;

import ensharp_scoring.example.ensharp_scoring.application.port.out.ResultParser;
import ensharp_scoring.example.ensharp_scoring.domain.model.TestExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

@Slf4j
@Component
public class JUnitXmlParser implements ResultParser {

    @Override
    public List<TestExecutionResult> parse(Path resultPath) {
        List<TestExecutionResult> results = new ArrayList<>();
        // Typically Gradle puts results in build/test-results/test/TEST-*.xml
        // We assume the docker run volume mapped path correctly and the container
        // generated results there.
        // Actually, in ProcessBuilderAdapter we logged output but result files need to
        // be accessed.
        // Since we mapped the directory, they should be in
        // 'directory/build/test-results/test'

        Path xmlDir = resultPath.resolve("build/test-results/test");
        if (!Files.exists(xmlDir)) {
            log.warn("Test result directory not found: {}", xmlDir);
            return Collections.emptyList();
        }

        try (Stream<Path> files = Files.walk(xmlDir)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".xml"))
                    .forEach(xmlFile -> results.addAll(parseXmlFile(xmlFile)));
        } catch (Exception e) {
            log.error("Failed to parse test results", e);
            return Collections.emptyList();
        }

        return results;
    }

    private List<TestExecutionResult> parseXmlFile(Path xmlFile) {
        List<TestExecutionResult> fileResults = new ArrayList<>();
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            Document doc = dBuilder.parse(xmlFile.toFile());
            doc.getDocumentElement().normalize();

            NodeList testCaseList = doc.getElementsByTagName("testcase");

            for (int i = 0; i < testCaseList.getLength(); i++) {
                Node node = testCaseList.item(i);
                if (node.getNodeType() == Node.ELEMENT_NODE) {
                    Element element = (Element) node;
                    String name = element.getAttribute("name");
                    String timeStr = element.getAttribute("time");
                    long duration = (long) (Double.parseDouble(timeStr) * 1000); // seconds to millis

                    NodeList failureList = element.getElementsByTagName("failure");
                    boolean passed = failureList.getLength() == 0;
                    String message = passed ? "Passed" : failureList.item(0).getTextContent();

                    fileResults.add(TestExecutionResult.builder()
                            .method(name)
                            .passed(passed)
                            .message(message)
                            .duration(duration)
                            .build());
                }
            }
        } catch (Exception e) {
            log.error("Error parsing XML file: {}", xmlFile, e);
        }
        return fileResults;
    }
}
