package com.loopers.architecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

@AnalyzeClasses(packages = "com.loopers", importOptions = ImportOption.DoNotIncludeTests.class)
class LayeredArchitectureTest {
    @ArchTest
    static final ArchRule layeredDependencies = layeredArchitecture().consideringAllDependencies()
        .layer("Interfaces").definedBy("..interfaces..")
        .layer("Application").definedBy("..application..")
        .layer("Domain").definedBy("..domain..")
        .layer("Infrastructure").definedBy("..infrastructure..")
        .whereLayer("Interfaces").mayNotBeAccessedByAnyLayer()
        .whereLayer("Application").mayOnlyBeAccessedByLayers("Interfaces")
        .whereLayer("Domain").mayOnlyBeAccessedByLayers("Application", "Infrastructure")
        .whereLayer("Infrastructure").mayNotBeAccessedByAnyLayer();
}
