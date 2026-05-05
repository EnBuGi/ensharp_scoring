package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.sandbox;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringStatus;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.TestCaseDto;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.TestDetail;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class JUnitXmlResultParser {

    public ScoringResult parse(String submissionId, List<File> xmlFiles, int exitCode, List<TestCaseDto> allowedTestCases, String buildLog) {
        log.info("[JUnitXmlResultParser] Starting parse: submissionId={}, exitCode={}, xmlFilesCount={}", 
                submissionId, exitCode, xmlFiles.size());
        
        // 1. Parse all XML results into a map for easy lookup
        Map<String, TestDetail> allResultsMap = new HashMap<>();

        for (File xmlFile : xmlFiles) {
            try {
                if (xmlFile != null && xmlFile.exists()) {
                    log.debug("[JUnitXmlResultParser] Parsing file: {}", xmlFile.getName());
                    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                    Document doc = dBuilder.parse(xmlFile);
                    doc.getDocumentElement().normalize();

                    NodeList testCaseList = doc.getElementsByTagName("testcase");
                    
                    for (int i = 0; i < testCaseList.getLength(); i++) {
                        Node testCaseNode = testCaseList.item(i);
                        if (testCaseNode.getNodeType() == Node.ELEMENT_NODE) {
                            Element testCaseElement = (Element) testCaseNode;
                            
                            String methodNameAttr = testCaseElement.getAttribute("methodName");
                            String nameAttr = testCaseElement.getAttribute("name");
                            String className = testCaseElement.getAttribute("classname");
                            
                            // Prioritize 'name' attribute (includes '()' for methods) to match apiServer's refined parsing.
                            // Fallback to 'methodName' or 'classname'.
                            String rawName = (nameAttr != null && !nameAttr.isEmpty()) 
                                    ? nameAttr 
                                    : (methodNameAttr != null && !methodNameAttr.isEmpty() ? methodNameAttr : className);
                            
                            // Match apiServer's behavior: append "()" to method names if not present.
                            // We assume it's a method if it's not the classname and doesn't have "()".
                            String actualMethodName = rawName;
                            if (rawName != null && !rawName.equals(className) && !rawName.contains("(")) {
                                actualMethodName = rawName + "()";
                            }
                            
                            log.info("[JUnitXmlResultParser] Parsed test case: rawName='{}', actualMethodName='{}', className='{}'", 
                                    rawName, actualMethodName, className);
                            
                            boolean isFailure = testCaseElement.getElementsByTagName("failure").getLength() > 0;
                            boolean isError = testCaseElement.getElementsByTagName("error").getLength() > 0;
                            boolean isSkipped = testCaseElement.getElementsByTagName("skipped").getLength() > 0;
                            
                            String message = null;
                            if (isFailure) {
                                message = testCaseElement.getElementsByTagName("failure").item(0).getTextContent();
                            } else if (isError) {
                                message = testCaseElement.getElementsByTagName("error").item(0).getTextContent();
                            }
                            
                            boolean passed = !isFailure && !isError && !isSkipped;
                            String status = passed ? "PASSED" : (isFailure ? "FAILED" : (isError ? "ERROR" : "SKIPPED"));

                            allResultsMap.put(actualMethodName, TestDetail.builder()
                                    .methodName(actualMethodName)
                                    .status(status)
                                    .durationMs(0L)
                                    .message(message)
                                    .build());
                        }
                    }
                }
            } catch (Exception e) {
                log.error("[JUnitXmlResultParser] Error parsing XML file {}: {}", xmlFile != null ? xmlFile.getName() : "null", e.getMessage());
            }
        }

        // 2. Filter based on allowedTestCases
        List<TestDetail> filteredDetails = new ArrayList<>();
        int passedCount = 0;

        log.info("[JUnitXmlResultParser] All identified results in XML: {}", allResultsMap.keySet());

        if (allowedTestCases == null || allowedTestCases.isEmpty()) {
            log.info("[JUnitXmlResultParser] allowedTestCases is empty, returning all found results");
            for (TestDetail detail : allResultsMap.values()) {
                filteredDetails.add(detail);
                if ("PASSED".equals(detail.getStatus())) {
                    passedCount++;
                }
            }
        } else {
            log.info("[JUnitXmlResultParser] Filtering results based on {} allowed test cases", allowedTestCases.size());
            for (TestCaseDto allowed : allowedTestCases) {
                String targetName = allowed.getName();
                TestDetail result = allResultsMap.get(targetName);
                if (result != null) {
                    log.info("[JUnitXmlResultParser] Match found: targetName='{}', status='{}'", targetName, result.getStatus());
                    filteredDetails.add(result);
                    if ("PASSED".equals(result.getStatus())) {
                        passedCount++;
                    }
                } else {
                    log.warn("[JUnitXmlResultParser] Match NOT found: targetName='{}'", targetName);
                    // Test case allowed by mentor but not found in execution (zip doesn't have it)
                    filteredDetails.add(TestDetail.builder()
                            .methodName(targetName)
                            .status("FAILED")
                            .message("Test case not found in execution results.")
                            .build());
                }
            }
        }

        ScoringStatus overallStatus;
        if (filteredDetails.isEmpty()) {
            overallStatus = (exitCode == 0) ? ScoringStatus.ACCEPTED : ScoringStatus.RUNTIME_ERROR;
        } else if (passedCount < filteredDetails.size()) {
            overallStatus = ScoringStatus.WRONG_ANSWER;
        } else {
            overallStatus = ScoringStatus.ACCEPTED;
        }
        
        // If exit code is non-zero (e.g., 1 or 137), but all tests passed in XML,
        // it means the JVM crashed after/during tests. This is a RUNTIME_ERROR.
        if (exitCode != 0 && overallStatus == ScoringStatus.ACCEPTED) {
            if (exitCode == 137 || buildLog.contains("OutMemoryError")) {
                overallStatus = ScoringStatus.MEMORY_LIMIT_EXCEEDED;
            } else {
                overallStatus = ScoringStatus.RUNTIME_ERROR;
            }
        }

        log.info("[JUnitXmlResultParser] Final result: passedCount={}, total={}, overallStatus={}", 
                passedCount, filteredDetails.size(), overallStatus);

        return ScoringResult.builder()
                .submissionId(submissionId)
                .overallStatus(overallStatus)
                .totalTests(filteredDetails.size())
                .passedTests(passedCount)
                .details(filteredDetails)
                .buildLog(truncateLog(buildLog))
                .build();
    }

    private String truncateLog(String log) {
        if (log == null || log.length() < 10000) return log;
        return log.substring(0, 5000) + "\n... [TRUNCATED] ...\n" + log.substring(log.length() - 5000);
    }
}
