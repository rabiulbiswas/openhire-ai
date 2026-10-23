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
                    "..communication.service..",
                    "..communication.controller..",
                    "..communication.repository..",
                    "..communication.client..",
                    "..communication.messaging..",
                    "..communication.scheduler..",
                    "..communication.config..");

    @ArchTest
    static final ArchRule servicesDoNotDependOnInboundLayers = noClasses()
            .that()
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.openhire..controller..", "com.openhire..scheduler..", "com.openhire..messaging.consumer..");
}
