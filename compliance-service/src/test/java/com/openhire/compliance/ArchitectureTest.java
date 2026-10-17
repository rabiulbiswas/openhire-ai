package com.openhire.compliance;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.openhire.compliance", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule domainIsIndependentOfApplicationAndAdapters = noClasses()
            .that()
            .resideInAPackage("..compliance.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..compliance.application..", "..compliance.adapter..", "..compliance.config..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = noClasses()
            .that()
            .resideInAPackage("..compliance.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..compliance.adapter..", "..compliance.config..");

    @ArchTest
    static final ArchRule applicationDoesNotUseWebOrKafkaOrRedis = noClasses()
            .that()
            .resideInAPackage("..compliance.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.kafka..", "org.springframework.data.redis..");
}
