package com.openhire.dispatch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.openhire.dispatch", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule domainIsIndependent = noClasses()
            .that()
            .resideInAPackage("..dispatch.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..dispatch.application..", "..dispatch.adapter..", "..dispatch.config..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = noClasses()
            .that()
            .resideInAPackage("..dispatch.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..dispatch.adapter..",
                    "org.springframework.mail..",
                    "org.springframework.kafka..",
                    "org.springframework.web..");
}
