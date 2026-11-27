package com.commercehub.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class ScenarioCatalogueTest {
  @Test
  void everyNonManualScenarioHasARealTestAndEveryTestTagExists() throws Exception {
    String catalogue = Files.readString(Path.of("docs/test-scenarios.md"));
    var rows = Pattern.compile("(?m)^\\| ([A-Z0-9]+-\\d{2}) \\|.*\\| (P[123]) \\| (.+) \\|$").matcher(catalogue);
    Set<String> ids = new HashSet<>();
    while (rows.find()) assertThat(ids.add(rows.group(1))).as("Unique scenario %s", rows.group(1)).isTrue();
    Set<String> tags = new HashSet<>();
    try (var files = Files.walk(Path.of("src/test"))) {
      for (Path file : files.filter(path -> path.toString().endsWith(".java"))
          .filter(path -> !path.endsWith("ScenarioCatalogueTest.java")).toList()) {
        var matches = Pattern.compile("\\[([A-Z0-9]+-\\d{2})\\]").matcher(Files.readString(file));
        while (matches.find()) tags.add(matches.group(1));
      }
    }
    assertThat(ids).hasSizeGreaterThanOrEqualTo(18);
    assertThat(tags).containsExactlyInAnyOrderElementsOf(ids);
    Set<String> positiveControl = new HashSet<>(ids);
    positiveControl.add("ZZZ-99");
    positiveControl.removeAll(tags);
    assertThat(positiveControl).containsExactly("ZZZ-99");
  }
}
