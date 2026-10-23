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
            .resideInAnyPackage(
                    "..compliance.service..",
                    "..compliance.controller..",
                    "..compliance.repository..",
                    "..compliance.client..",
                    "..compliance.messaging..",
                    "..compliance.scheduler..",
                    "..compliance.config..");

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
            .resideInAPackage("..compliance.service..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.kafka..", "org.springframework.data.redis..");
}
