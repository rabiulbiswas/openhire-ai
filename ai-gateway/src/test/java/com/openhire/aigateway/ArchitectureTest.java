package com.openhire.aigateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.openhire.aigateway", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule domainIsIndependentOfApplicationAndAdapters = noClasses()
            .that()
            .resideInAPackage("..aigateway.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..aigateway.service..",
                    "..aigateway.controller..",
                    "..aigateway.repository..",
                    "..aigateway.client..",
                    "..aigateway.messaging..",
                    "..aigateway.scheduler..",
                    "..aigateway.config..");

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
            .resideInAPackage("..aigateway.service..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.kafka..", "org.springframework.data.redis..");

    @ArchTest
    static final ArchRule onlyProviderAdaptersTouchHttpClients = noClasses()
            .that()
            .resideOutsideOfPackage("..aigateway.client.provider..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.client.RestClient");
}
