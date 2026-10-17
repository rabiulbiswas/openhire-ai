package com.openhire.analytics;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.openhire.analytics", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule domainIsIndependentOfApplicationAndAdapters = noClasses()
            .that()
            .resideInAPackage("..analytics.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..analytics.application..", "..analytics.adapter..", "..analytics.config..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = noClasses()
            .that()
            .resideInAPackage("..analytics.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..analytics.adapter..", "..analytics.config..");

    @ArchTest
    static final ArchRule applicationDoesNotUseWebKafkaRedisOrMongo = noClasses()
            .that()
            .resideInAPackage("..analytics.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web..",
                    "org.springframework.kafka..",
                    "org.apache.kafka..",
                    "org.springframework.data..");
}
