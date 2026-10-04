package com.neuringo.neuringobe;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.library.GeneralCodingRules;
import jakarta.persistence.Entity;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * AI 호출 경계(backend/docs/ai-provider-contract.md "호출 계층"): 애플리케이션 로직은 특정 LLM SDK 에 직접 의존하지 않는다.
 *
 * <ul>
 *   <li>ai.application 은 ai.infrastructure·ai.config 를 모른다. 제공자 중립 포트(LlmProvider)만 쓴다.
 *   <li>ai.application 은 Spring AI·OpenAI SDK 타입을 쓰지 않는다. 그 타입은
 *       infrastructure(SpringAiLlmProvider)와 이를 조립하는 config 에만 둔다.
 * </ul>
 *
 * <p>계층 방향(PR #16 멘토 리뷰에 따라 #17 에서 도입한 구조): controller → service → repository. Controller 는
 * Repository 를 직접 쓰지 않고 Service 를 거친다. 거꾸로 가는 의존(service → controller 등)도 막는다. 세 계층 밖의
 * 패키지(domain·dto·common·config·ai)는 보지 않는다.
 *
 * <p>#17 구조를 지키는 규칙:
 *
 * <ul>
 *   <li>Controller 는 JPA 엔티티를 쓰지 않는다. 응답은 dto 의 record 로만 내보낸다(06 DoD "민감 데이터가 불필요하게 응답에 남지 않는다" —
 *       엔티티에 컬럼이 늘어도 응답에 저절로 실리지 않는다).
 *   <li>Spring Data Repository 는 repository 패키지에 둔다(계층 규칙이 패키지 이름으로 계층을 찾는다).
 *   <li>@Transactional 은 service 에만 쓴다. 트랜잭션 경계를 한곳에서 본다.
 *   <li>common 은 도메인 패키지(classroom·child·ai·auth·user)를 모른다. 도메인끼리는 순환 의존이 없다(01 도메인 책임 맵 — 도메인마다
 *       주인이 다르다).
 * </ul>
 *
 * <p>일반 규칙: 필드 주입을 쓰지 않는다(생성자 주입). System.out·printStackTrace·java.util.logging 을 쓰지 않는다 — 로그는
 * SLF4J 로만 남겨야 로그 개인정보 규칙(VS-017, 아동 이름·발화를 로그에 남기지 않는다)을 한곳에서 지킬 수 있다.
 *
 * <p>ArchUnit 은 core 만 쓰고 일반 JUnit 테스트 안에서 규칙을 검사한다(별도 테스트 엔진 없음).
 */
class ArchitectureTest {

    private static final JavaClasses MAIN_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("com.neuringo.neuringobe");

    @Test
    void aiApplicationDoesNotDependOnInfrastructureOrConfig() {
        noClasses()
                .that()
                .resideInAPackage("..ai.application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("..ai.infrastructure..", "..ai.config..")
                .because("ai.application 은 LlmProvider 포트만 알고, 구현과 설정은 바깥에서 주입받는다")
                .check(MAIN_CLASSES);
    }

    @Test
    void aiApplicationDoesNotUseProviderSdks() {
        noClasses()
                .that()
                .resideInAPackage("..ai.application..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("org.springframework.ai..", "com.openai..")
                .because("제공자를 바꿔도 애플리케이션 로직은 그대로여야 한다")
                .check(MAIN_CLASSES);
    }

    @Test
    void controllersReachRepositoriesOnlyThroughServices() {
        layeredArchitecture()
                .consideringOnlyDependenciesInLayers()
                .layer("Controller")
                .definedBy("..controller..")
                .layer("Service")
                .definedBy("..service..")
                .layer("Repository")
                .definedBy("..repository..")
                .whereLayer("Controller")
                .mayNotBeAccessedByAnyLayer()
                .whereLayer("Service")
                .mayOnlyBeAccessedByLayers("Controller")
                .whereLayer("Repository")
                .mayOnlyBeAccessedByLayers("Service")
                .because("소유권·상태 규칙을 Service 한곳에 모으려면 Controller 가 Repository 를 직접 쓰면 안 된다")
                .check(MAIN_CLASSES);
    }

    @Test
    void controllersDoNotExposeEntities() {
        noClasses()
                .that()
                .resideInAPackage("..controller..")
                .should()
                .dependOnClassesThat()
                .areAnnotatedWith(Entity.class)
                .because("엔티티에 컬럼이 늘어도 응답에 저절로 실리지 않도록 응답은 dto 로만 내보낸다")
                .check(MAIN_CLASSES);
    }

    @Test
    void requestAndResponseTypesAreRecords() {
        classes()
                .that()
                .resideInAPackage("..dto..")
                .should()
                .beRecords()
                .because("요청·응답 값은 만든 뒤 바뀌지 않아야 한다")
                .check(MAIN_CLASSES);
    }

    @Test
    void repositoriesLiveInRepositoryPackages() {
        classes()
                .that()
                .areAssignableTo(Repository.class)
                .should()
                .resideInAPackage("..repository..")
                .because("계층 규칙(controllersReachRepositoriesOnlyThroughServices)은 패키지 이름으로 계층을 찾는다")
                .check(MAIN_CLASSES);
    }

    @Test
    void transactionsAreDeclaredOnlyInServices() {
        noClasses()
                .that()
                .resideOutsideOfPackage("..service..")
                .should()
                .beAnnotatedWith(Transactional.class)
                .orShould()
                .beAnnotatedWith(jakarta.transaction.Transactional.class)
                .because("트랜잭션 경계를 Service 한곳에서 본다")
                .check(MAIN_CLASSES);
        noMethods()
                .that()
                .areDeclaredInClassesThat()
                .resideOutsideOfPackage("..service..")
                .should()
                .beAnnotatedWith(Transactional.class)
                .orShould()
                .beAnnotatedWith(jakarta.transaction.Transactional.class)
                .because("트랜잭션 경계를 Service 한곳에서 본다")
                .check(MAIN_CLASSES);
    }

    @Test
    void commonDoesNotDependOnDomains() {
        noClasses()
                .that()
                .resideInAPackage("..neuringobe.common..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage(
                        "..neuringobe.classroom..",
                        "..neuringobe.child..",
                        "..neuringobe.ai..",
                        "..neuringobe.auth..",
                        "..neuringobe.user..")
                .because("공통 응답·오류 형식은 어느 도메인에도 기대지 않아야 모든 도메인이 같이 쓴다")
                .check(MAIN_CLASSES);
    }

    @Test
    void domainsAreFreeOfCycles() {
        slices().matching("com.neuringo.neuringobe.(*)..")
                .should()
                .beFreeOfCycles()
                .because("도메인마다 주인이 다르다(01 도메인 책임 맵). 서로 물고 물리면 한쪽만 고칠 수 없다")
                .check(MAIN_CLASSES);
    }

    @Test
    void doesNotUseFieldInjection() {
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_FIELD_INJECTION.check(MAIN_CLASSES);
    }

    @Test
    void logsOnlyThroughSlf4j() {
        GeneralCodingRules.NO_CLASSES_SHOULD_ACCESS_STANDARD_STREAMS.check(MAIN_CLASSES);
        GeneralCodingRules.NO_CLASSES_SHOULD_USE_JAVA_UTIL_LOGGING.check(MAIN_CLASSES);
    }
}
