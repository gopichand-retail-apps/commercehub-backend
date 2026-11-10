package com.commercehub;

import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

class CommerceHubApplicationTest {
  @Test
  void delegatesStartupToSpringApplication() {
    String[] arguments = {"--app.environment=test"};

    try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
      CommerceHubApplication.main(arguments);

      springApplication.verify(() -> SpringApplication.run(CommerceHubApplication.class, arguments));
    }
  }
}
