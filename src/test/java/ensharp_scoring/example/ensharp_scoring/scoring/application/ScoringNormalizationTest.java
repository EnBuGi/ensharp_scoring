package ensharp_scoring.example.ensharp_scoring.scoring.application;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScoringNormalizationTest {

    private ScoringService scoringService;
    private Set<String> projectClassNames;
    private Set<String> originalPackages;

    @BeforeEach
    void setUp() {
        // ScoringService의 의존성은 테스트에 필요하지 않으므로 null로 주입하거나 Mock 없이 생성 (정규화 로직만 테스트)
        scoringService = new ScoringService(null, null, null, null);
        projectClassNames = Set.of("Shape", "Pyramid", "IoManager", "Main");
        originalPackages = Set.of("draw", "ui", "com.student.logic");
    }

    @Test
    @DisplayName("원래 패키지의 와일드카드 import는 주석 처리되어야 한다")
    void wildcardImportFromOriginalPackageShouldBeCommentedOut() {
        String content = "package draw;\nimport draw.*;\nimport java.util.*;\npublic class Shape {}";
        String processed = scoringService.processJavaFileContent(content, projectClassNames, originalPackages, "Shape.java");

        assertThat(processed).contains("// Redundant wildcard import removed: import draw.*;");
        assertThat(processed).contains("import java.util.*;");
        assertThat(processed).contains("package gs.submission;");
    }

    @Test
    @DisplayName("프로젝트 내 클래스의 특정 import는 주석 처리되어야 한다")
    void specificImportToProjectClassShouldBeCommentedOut() {
        String content = "package ui;\nimport draw.Shape;\nimport java.util.List;\npublic class IoManager {}";
        String processed = scoringService.processJavaFileContent(content, projectClassNames, originalPackages, "IoManager.java");

        assertThat(processed).contains("// Redundant internal import removed: import draw.Shape;");
        assertThat(processed).contains("import java.util.List;");
    }

    @Test
    @DisplayName("계층형 패키지의 와일드카드 import도 처리되어야 한다")
    void nestedWildcardImportShouldBeCommentedOut() {
        String content = "import com.student.logic.*;\npublic class Main {}";
        String processed = scoringService.processJavaFileContent(content, projectClassNames, originalPackages, "Main.java");

        assertThat(processed).contains("// Redundant wildcard import removed: import com.student.logic.*;");
    }

    @Test
    @DisplayName("프로젝트 패키지를 경로로 가지는 클래스 import도 처리되어야 한다")
    void specificImportUsingOriginalPackageShouldBeCommentedOut() {
        String content = "import draw.UnknownClass;\npublic class Main {}";
        // UnknownClass는 projectClassNames에 없지만, draw 패키지가 originalPackages에 있으므로 제거되어야 함
        String processed = scoringService.processJavaFileContent(content, projectClassNames, originalPackages, "Main.java");

        assertThat(processed).contains("// Redundant internal import removed: import draw.UnknownClass;");
    }

    @Test
    @DisplayName("중복되거나 이미 패키지가 있는 경우에도 패키지 선언이 gs.submission으로 유지되어야 한다")
    void packageDeclarationShouldBeNormalized() {
        String content = "/* comment */\npackage original.pkg;\npublic class Test {}";
        String processed = scoringService.processJavaFileContent(content, projectClassNames, originalPackages, "Test.java");

        assertThat(processed).contains("package gs.submission;");
        assertThat(processed).doesNotContain("package original.pkg;");
    }
}
