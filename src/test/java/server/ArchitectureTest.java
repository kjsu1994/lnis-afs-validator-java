package server;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** 단일 Gradle 프로젝트에서도 기존 모듈의 의존 방향을 package 규칙으로 보존한다. */
@AnalyzeClasses(packages = "server", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
  @ArchTest
  static final ArchRule shared_is_independent =
      noClasses()
          .that()
          .resideInAPackage("server.shared..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("server.agent..", "server.central..");

  @ArchTest
  static final ArchRule agent_does_not_depend_on_central =
      noClasses()
          .that()
          .resideInAPackage("server.agent..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("server.central..");

  /** 중앙 서버는 Agent의 실행 구현 대신 shared의 통신 계약만 사용한다. */
  @ArchTest
  static final ArchRule central_does_not_depend_on_agent =
      noClasses()
          .that()
          .resideInAPackage("server.central..")
          .should()
          .dependOnClassesThat()
          .resideInAPackage("server.agent..");
}
