package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.sandbox;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringStatus;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.TestCaseDto;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.TestDetail;
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

@Component
public class JUnitXmlResultParser {

    public ScoringResult parse(String submissionId, List<File> xmlFiles, int exitCode, List<TestCaseDto> allowedTestCases) {
        // 1. Parse all XML results into a map for easy lookup
        Map<String, TestDetail> allResultsMap = new HashMap<>();

        for (File xmlFile : xmlFiles) {
            try {
                if (xmlFile != null && xmlFile.exists()) {
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
                            
                            String finalMethodName = (methodNameAttr != null && !methodNameAttr.isEmpty()) 
                                    ? methodNameAttr 
                                    : (nameAttr != null && !nameAttr.isEmpty() ? nameAttr : className);
                            
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

                            allResultsMap.put(finalMethodName, TestDetail.builder()
                                    .methodName(finalMethodName)
                                    .status(status)
                                    .durationMs(0L)
                                    .message(message)
                                    .build());
                        }
                    }
                }
            } catch (Exception e) {
                // Ignore parsing issues for individual files
            }
        }

        // 2. Filter and Score based on allowedTestCases
        List<TestDetail> filteredDetails = new ArrayList<>();
        int passedCount = 0;
        int totalScore = 0;

        if (allowedTestCases == null || allowedTestCases.isEmpty()) {
            // Fallback for empty allowed list: include all results (might happen if old request)
            for (TestDetail detail : allResultsMap.values()) {
                filteredDetails.add(detail);
                if ("PASSED".equals(detail.getStatus())) {
                    passedCount++;
                }
            }
        } else {
            for (TestCaseDto allowed : allowedTestCases) {
                TestDetail result = allResultsMap.get(allowed.getName());
                if (result != null) {
                    filteredDetails.add(result);
                    if ("PASSED".equals(result.getStatus())) {
                        passedCount++;
                        totalScore += allowed.getScore();
                    }
                } else {
                    // Test case allowed by mentor but not found in execution (zip doesn't have it)
                    filteredDetails.add(TestDetail.builder()
                            .methodName(allowed.getName())
                            .status("FAILED")
                            .message("Test case not found in execution results.")
                            .build());
                }
            }
        }

        ScoringStatus overallStatus = (filteredDetails.isEmpty() || passedCount < filteredDetails.size()) 
                ? ScoringStatus.WRONG_ANSWER 
                : ScoringStatus.ACCEPTED;
        
        if (exitCode != 0 && passedCount == filteredDetails.size()) {
            overallStatus = ScoringStatus.RUNTIME_ERROR;
        }

        return ScoringResult.builder()
                .submissionId(submissionId)
                .overallStatus(overallStatus)
                .totalTests(filteredDetails.size())
                .passedTests(passedCount)
                .totalScore(totalScore)
                .details(filteredDetails)
                .build();
    }
}
