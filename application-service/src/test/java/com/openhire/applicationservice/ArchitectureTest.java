package com.openhire.applicationservice;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import org.springframework.web.bind.annotation.RestController;

@AnalyzeClasses(packages = "com.openhire.applicationservice", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {
    @ArchTest
    static final ArchRule servicesDoNotDependOnControllers = noClasses()
            .that()
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..controller..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllersHaveTheirOwnPackage =
            classes().that().areAnnotatedWith(RestController.class).should().resideInAPackage("..controller..");

    @ArchTest
    static final ArchRule domainDoesNotDependOnOuterLayers = noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "..controller..",
                    "..service..",
                    "..client..",
                    "..repository..",
                    "..messaging..",
                    "..scheduler..",
                    "..config..")
            .allowEmptyShould(true);
}
