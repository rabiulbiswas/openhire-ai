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
            .resideInAnyPackage(
                    "..dispatch.service..",
                    "..dispatch.controller..",
                    "..dispatch.repository..",
                    "..dispatch.client..",
                    "..dispatch.messaging..",
                    "..dispatch.scheduler..",
                    "..dispatch.config..");

    @ArchTest
    static final ArchRule servicesDoNotDependOnInboundLayers = noClasses()
            .that()
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.openhire..controller..", "com.openhire..scheduler..", "com.openhire..messaging.consumer..");
}
