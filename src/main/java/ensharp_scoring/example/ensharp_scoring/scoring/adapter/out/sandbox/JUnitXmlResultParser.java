package ensharp_scoring.example.ensharp_scoring.scoring.adapter.out.sandbox;

import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringResult;
import ensharp_scoring.example.ensharp_scoring.scoring.domain.ScoringStatus;
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
import java.util.List;

@Component
public class JUnitXmlResultParser {

    public ScoringResult parse(String submissionId, List<File> xmlFiles, int exitCode) {
        List<TestDetail> details = new ArrayList<>();
        int totalTestsCount = 0;
        int failedTestsCount = 0;
        int errorTestsCount = 0;

        for (File xmlFile : xmlFiles) {
            try {
                if (xmlFile != null && xmlFile.exists()) {
                    DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
                    DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
                    Document doc = dBuilder.parse(xmlFile);
                    doc.getDocumentElement().normalize();

                    NodeList testSuiteList = doc.getElementsByTagName("testsuite");
                    
                    for (int i = 0; i < testSuiteList.getLength(); i++) {
                        Node testSuiteNode = testSuiteList.item(i);
                        if (testSuiteNode.getNodeType() == Node.ELEMENT_NODE) {
                            Element testSuiteElement = (Element) testSuiteNode;
                            
                            try {
                                totalTestsCount += Integer.parseInt(testSuiteElement.getAttribute("tests"));
                                failedTestsCount += Integer.parseInt(testSuiteElement.getAttribute("failures"));
                                errorTestsCount += Integer.parseInt(testSuiteElement.getAttribute("errors"));
                            } catch (NumberFormatException e) {
                                // Ignore parsing issues for attributes and continue with elements
                            }
                        }
                    }
                    
                    NodeList testCaseList = doc.getElementsByTagName("testcase");
                    
                    for (int i = 0; i < testCaseList.getLength(); i++) {
                        Node testCaseNode = testCaseList.item(i);
                        if (testCaseNode.getNodeType() == Node.ELEMENT_NODE) {
                            Element testCaseElement = (Element) testCaseNode;
                            
                            // methodName 속성이 있으면 그것을 우선 사용 (JUnit 5 + Gradle 조합에서 displayName 대신 메서드명을 얻기 위함)
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
                            
                            details.add(TestDetail.builder()
                                    .methodName(finalMethodName)
                                    .status(passed ? "PASSED" : (isFailure ? "FAILED" : (isError ? "ERROR" : "SKIPPED")))
                                    .durationMs(0L)
                                    .message(message)
                                    .build());
                        }
                    }
                }
            } catch (Exception e) {
                // Log parsing error for this specific file, but continue with others
                failedTestsCount++; 
            }
        }

        boolean hasFailures = failedTestsCount > 0 || errorTestsCount > 0;
        ScoringStatus status;
        
        if (hasFailures) {
            status = ScoringStatus.WRONG_ANSWER;
        } else if (exitCode != 0) {
            status = ScoringStatus.RUNTIME_ERROR;
        } else {
            status = ScoringStatus.ACCEPTED;
        }

        return ScoringResult.builder()
                .submissionId(submissionId)
                .overallStatus(status)
                .totalTests(totalTestsCount > 0 ? totalTestsCount : details.size())
                .passedTests(totalTestsCount > 0 ? (totalTestsCount - failedTestsCount - errorTestsCount) : (int) details.stream().filter(d -> "PASSED".equals(d.getStatus())).count())
                .details(details)
                .build();
    }
}
