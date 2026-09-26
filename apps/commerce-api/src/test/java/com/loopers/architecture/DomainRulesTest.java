package com.loopers.architecture;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * domain-rules.yaml 이 테스트·요구사항 문서와 어긋나지 않는지 본다.
 * 마크다운은 표를 파싱하지 않고 ID 토큰만 걷는다 — 표 형식이 한 행만 달라도 파싱은 깨진다.
 */
class DomainRulesTest {

    private static final String RULES_FILE = "docs/week2/domain-rules.yaml";
    private static final String REQUIREMENTS_FILE = "docs/week2/requirements.md";
    private static final String TEST_SOURCE_DIR = "/src/test/java/";
    private static final String SELF = "DomainRulesTest.java";

    /** 규칙 ID 를 찾는 자리는 @Nested 가 붙은 @DisplayName 맨 앞 대괄호 하나뿐이다. */
    private static final Pattern RULE_ID_IN_NESTED =
        Pattern.compile("@DisplayName\\(\"\\[(INV-\\d+)\\][^\"]*\"\\)\\s*\\R\\s*@Nested");

    /** 요구사항·정책 ID. 문서 어디에 적혀 있든 형식이 같다. */
    private static final Pattern REQUIREMENT_ID = Pattern.compile("\\b[RP]-[A-Z]+-\\d+\\b");

    @DisplayName("domain-rules.yaml 의 규칙은 모두 @Nested 의 @DisplayName 에 나타난다.")
    @Test
    void everyRuleAppearsInNestedDisplayName() throws IOException {
        // arrange
        Set<String> defined = definedRuleIds(readDomainRules());

        // act
        Set<String> tested = scanRuleIdsInTestSources();

        // assert
        assertThat(defined).isNotEmpty();
        assertThat(defined).isSubsetOf(tested);
    }

    @DisplayName("요구사항이 가리키는 규칙과 정의된 규칙은 서로 같다.")
    @Test
    void referencedRulesAreExactlyTheDefinedRules() throws IOException {
        // arrange
        DomainRules rules = readDomainRules();
        Set<String> defined = definedRuleIds(rules);

        // act
        Set<String> referenced = rules.requirements().values().stream()
            .flatMap(List::stream)
            .collect(Collectors.toSet());

        // assert
        assertThat(defined).isNotEmpty();
        assertThat(referenced).containsExactlyInAnyOrderElementsOf(defined);
    }

    @DisplayName("domain-rules.yaml 의 요구사항은 requirements.md 의 요구사항과 서로 같다.")
    @Test
    void requirementsMatchTheRequirementsDocument() throws IOException {
        // arrange
        Set<String> inRules = readDomainRules().requirements().keySet();

        // act
        Set<String> inDocument = requirementIdsIn(repositoryRoot().resolve(REQUIREMENTS_FILE));

        // assert
        assertThat(inDocument).isNotEmpty();
        assertThat(inRules).containsExactlyInAnyOrderElementsOf(inDocument);
    }

    private static DomainRules readDomainRules() throws IOException {
        // 구조가 어긋나면 알 수 없는 속성으로 실패한다.
        return new ObjectMapper(new YAMLFactory())
            .readValue(repositoryRoot().resolve(RULES_FILE).toFile(), DomainRules.class);
    }

    private static Set<String> definedRuleIds(DomainRules rules) {
        return rules.rules().values().stream()
            .flatMap(group -> group.keySet().stream())
            .collect(Collectors.toSet());
    }

    private static Set<String> requirementIdsIn(Path document) {
        return REQUIREMENT_ID.matcher(read(document)).results()
            .map(result -> result.group())
            .collect(Collectors.toSet());
    }

    private static Set<String> scanRuleIdsInTestSources() throws IOException {
        try (Stream<Path> paths = Files.walk(repositoryRoot())) {
            return paths
                .filter(DomainRulesTest::isScannableTestSource)
                .flatMap(DomainRulesTest::ruleIdsIn)
                .collect(Collectors.toSet());
        }
    }

    private static boolean isScannableTestSource(Path path) {
        String location = path.toString().replace('\\', '/');
        return location.endsWith(".java")
            && location.contains(TEST_SOURCE_DIR)
            && !location.contains("/build/")
            && !location.endsWith(SELF);
    }

    private static Stream<String> ruleIdsIn(Path source) {
        Matcher matcher = RULE_ID_IN_NESTED.matcher(read(source));
        return matcher.results().map(result -> result.group(1));
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path repositoryRoot() {
        Path candidate = Paths.get("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve(RULES_FILE))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException(RULES_FILE + " 을 찾지 못했다.");
        }
        return candidate;
    }

    private record DomainRules(
        Map<String, List<String>> requirements,
        Map<String, Map<String, String>> rules
    ) {
    }
}
