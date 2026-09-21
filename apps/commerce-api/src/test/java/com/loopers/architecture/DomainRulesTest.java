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
 * domain-rules.yaml 의 규칙이 모두 테스트에 나타나는지 본다.
 * 읽는 것은 domain-rules.yaml 과 테스트 소스 둘뿐이고, 마크다운은 읽지 않는다.
 */
class DomainRulesTest {

    private static final String RULES_FILE = "docs/week2/domain-rules.yaml";
    private static final String TEST_SOURCE_DIR = "/src/test/java/";
    private static final String SELF = "DomainRulesTest.java";

    /** 규칙 ID 를 찾는 자리는 @Nested 가 붙은 @DisplayName 맨 앞 대괄호 하나뿐이다. */
    private static final Pattern RULE_ID_IN_NESTED =
        Pattern.compile("@DisplayName\\(\"\\[(INV-\\d+)\\][^\"]*\"\\)\\s*\\R\\s*@Nested");

    @DisplayName("domain-rules.yaml 의 규칙은 모두 @Nested 의 @DisplayName 에 나타난다.")
    @Test
    void everyRuleAppearsInNestedDisplayName() throws IOException {
        // arrange
        Set<String> defined = readDomainRules().rules().values().stream()
            .flatMap(group -> group.keySet().stream())
            .collect(Collectors.toSet());

        // act
        Set<String> tested = scanRuleIdsInTestSources();

        // assert
        assertThat(defined).isNotEmpty();
        assertThat(defined).isSubsetOf(tested);
    }

    private static DomainRules readDomainRules() throws IOException {
        // 구조가 어긋나면 알 수 없는 속성으로 실패한다.
        return new ObjectMapper(new YAMLFactory())
            .readValue(repositoryRoot().resolve(RULES_FILE).toFile(), DomainRules.class);
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
