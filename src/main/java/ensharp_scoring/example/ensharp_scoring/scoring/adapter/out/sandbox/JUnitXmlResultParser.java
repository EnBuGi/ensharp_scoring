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

    public ScoringResult parse(String submissionId, File xmlFile, int exitCode) {
        List<TestDetail> details = new ArrayList<>();
        int totalTests = 0;
        int failedTests = 0;
        int errorTests = 0;
        int skippedTests = 0;

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
                            totalTests += Integer.parseInt(testSuiteElement.getAttribute("tests"));
                            failedTests += Integer.parseInt(testSuiteElement.getAttribute("failures"));
                            errorTests += Integer.parseInt(testSuiteElement.getAttribute("errors"));
                            skippedTests += Integer.parseInt(testSuiteElement.getAttribute("skipped"));
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
                        String testName = testCaseElement.getAttribute("name");
                        String className = testCaseElement.getAttribute("classname");
                        
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
                                .methodName(testName != null && !testName.isEmpty() ? testName : className)
                                .status(passed ? "PASSED" : (isFailure ? "FAILED" : (isError ? "ERROR" : "SKIPPED")))
                                .durationMs(0L) // XML parsing doesn't provide precise duration here ideally, setting to 0
                                .message(message)
                                .build());
                    }
                }
            }
        } catch (Exception e) {
            // Log parsing error, but let scoring status decide outcome
            failedTests = 1; // Mark as failed to avoid false AC
        }

        boolean hasFailures = failedTests > 0 || errorTests > 0;
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
                .totalTests(totalTests > 0 ? totalTests : details.size())
                .passedTests(totalTests > 0 ? (totalTests - failedTests - errorTests) : (int) details.stream().filter(d -> "PASSED".equals(d.getStatus())).count())
                .details(details)
                .build();
    }
}
