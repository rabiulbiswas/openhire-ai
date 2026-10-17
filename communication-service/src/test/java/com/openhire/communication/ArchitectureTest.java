package com.openhire.communication;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.openhire.communication", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule domainIsIndependent = noClasses()
            .that()
            .resideInAPackage("..communication.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..communication.application..", "..communication.adapter..", "..communication.config..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = noClasses()
            .that()
            .resideInAPackage("..communication.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..communication.adapter..",
                    "..communication.config..",
                    "org.springframework.web..",
                    "org.springframework.kafka..");
}
