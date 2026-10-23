package com.openhire.offer;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.openhire.offer", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule domainIsIndependentOfApplicationAndAdapters = noClasses()
            .that()
            .resideInAPackage("..offer.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..offer.service..",
                    "..offer.controller..",
                    "..offer.repository..",
                    "..offer.client..",
                    "..offer.messaging..",
                    "..offer.scheduler..",
                    "..offer.config..");

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
            .resideInAPackage("..offer.service..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.kafka..", "org.springframework.data.redis..");
}
