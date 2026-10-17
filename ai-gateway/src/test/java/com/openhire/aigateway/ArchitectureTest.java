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
            .resideInAnyPackage("..aigateway.application..", "..aigateway.adapter..", "..aigateway.config..");

    @ArchTest
    static final ArchRule applicationDoesNotDependOnAdapters = noClasses()
            .that()
            .resideInAPackage("..aigateway.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..aigateway.adapter..", "..aigateway.config..");

    @ArchTest
    static final ArchRule applicationDoesNotUseWebOrKafkaOrRedis = noClasses()
            .that()
            .resideInAPackage("..aigateway.application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.web..", "org.springframework.kafka..", "org.springframework.data.redis..");

    @ArchTest
    static final ArchRule onlyProviderAdaptersTouchHttpClients = noClasses()
            .that()
            .resideOutsideOfPackage("..aigateway.adapter.out.provider..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("org.springframework.web.client.RestClient");
}
