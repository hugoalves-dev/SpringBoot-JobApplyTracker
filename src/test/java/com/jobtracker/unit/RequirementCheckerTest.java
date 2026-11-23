package com.jobtracker.unit;

import com.jobtracker.service.semantic.RequirementChecker;
import com.jobtracker.service.semantic.RequirementChecker.RequirementCheck;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementCheckerTest {
    @Test
    void checksTermsWithTokenBoundariesCaseAndAccentInsensitive() {
        List<RequirementCheck> checks = RequirementChecker.check(
                List.of("Java", "remoto", "Sênior", "C#", "Kotlin"),
                "Desenvolvedor Senior", "ACME", "Stack: JavaScript, C#, trabalho REMOTO, salário R$ 15k");

        assertThat(checks).extracting(RequirementCheck::term, RequirementCheck::present).containsExactly(
                org.assertj.core.groups.Tuple.tuple("Java", false),
                org.assertj.core.groups.Tuple.tuple("remoto", true),
                org.assertj.core.groups.Tuple.tuple("Sênior", true),
                org.assertj.core.groups.Tuple.tuple("C#", true),
                org.assertj.core.groups.Tuple.tuple("Kotlin", false));
        assertThat(checks.get(1).evidence()).contains("REMOTO");
        assertThat(checks.get(4).evidence()).isNull();
    }

    @Test
    void cDoesNotMatchCSharpOrCPlusPlus() {
        assertThat(RequirementChecker.check(List.of("C"), "C# and C++ only").getFirst().present()).isFalse();
    }

    @Test
    void ignoresBlankTermsAndNullTexts() {
        assertThat(RequirementChecker.check(List.of(" ", "Go"), null, "Go developer"))
                .singleElement().satisfies(c -> assertThat(c.present()).isTrue());
        assertThat(RequirementChecker.check(null, "anything")).isEmpty();
    }
}
