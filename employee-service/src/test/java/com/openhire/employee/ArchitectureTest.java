package com.openhire.employee;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.openhire.employee", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule domainIsIndependentOfApplicationAndAdapters = noClasses()
            .that()
            .resideInAPackage("..employee.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..employee.service..",
                    "..employee.controller..",
                    "..employee.repository..",
                    "..employee.client..",
                    "..employee.messaging..",
                    "..employee.scheduler..",
                    "..employee.config..");

    @ArchTest
    static final ArchRule servicesDoNotDependOnInboundLayers = noClasses()
            .that()
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.openhire..controller..", "com.openhire..scheduler..", "com.openhire..messaging.consumer..");

    @ArchTest
    static final ArchRule applicationDoesNotUseWebOrKafkaOrRedis = noClasses()
            .that()
            .resideInAPackage("..employee.service..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.kafka..", "org.springframework.data.redis..");
}
