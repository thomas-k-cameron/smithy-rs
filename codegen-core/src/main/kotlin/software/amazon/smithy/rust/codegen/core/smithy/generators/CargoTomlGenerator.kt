/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.generators

import com.moandjiezana.toml.TomlWriter
import software.amazon.smithy.rust.codegen.core.Version
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.DependencyScope
import software.amazon.smithy.rust.codegen.core.rustlang.Feature
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.smithy.CoreRustSettings
import software.amazon.smithy.rust.codegen.core.util.deepMergeWith

/**
 * Customizations to apply to the generated Cargo.toml file.
 *
 * This is a nested map of key/value that represents the properties in a crate manifest.
 * For example, the following
 *
 * ```kotlin
 * mapOf(
 *     "package" to mapOf(
 *         "name" to "foo",
 *         "version" to "1.0.0",
 *     )
 * )
 * ```
 *
 * is equivalent to
 *
 * ```toml
 * [package]
 * name = "foo"
 * version = "1.0.0"
 * ```
 */
typealias ManifestCustomizations = Map<String, Any?>

/**
 * Generates the crate manifest Cargo.toml file.
 */
class CargoTomlGenerator(
    private val moduleName: String,
    private val moduleVersion: String,
    private val moduleAuthors: List<String>,
    private val moduleDescription: String?,
    private val moduleLicense: String?,
    private val moduleRepository: String?,
    private val writer: RustWriter,
    private val manifestCustomizations: ManifestCustomizations = emptyMap(),
    private val dependencies: List<CargoDependency> = emptyList(),
    private val features: List<Feature> = emptyList(),
) {
    constructor(
        settings: CoreRustSettings,
        writer: RustWriter,
        manifestCustomizations: ManifestCustomizations,
        dependencies: List<CargoDependency>,
        features: List<Feature>,
    ) : this(
        settings.moduleName,
        settings.moduleVersion,
        settings.moduleAuthors,
        settings.moduleDescription,
        settings.license,
        settings.moduleRepository,
        writer,
        manifestCustomizations,
        dependencies,
        features,
    )

    fun render() {
        val cargoFeatures = features.map { it.name to it.deps }.toMutableList()
        if (features.isNotEmpty()) {
            cargoFeatures.add("default" to features.filter { it.default }.map { it.name })
        }

        val cargoToml = mapOf(
            "package" to listOfNotNull(
                "name" to moduleName,
                "version" to moduleVersion,
                "authors" to moduleAuthors,
                moduleDescription?.let { "description" to it },
                "edition" to "2021",
                "license" to moduleLicense,
                "repository" to moduleRepository,
                "metadata" to listOfNotNull(
                    "smithy" to listOfNotNull(
                        "codegen-version" to Version.fullVersion(),
                    ).toMap(),
                ).toMap(),
            ).toMap(),
            "dependencies" to dependencies.filter { it.scope == DependencyScope.Compile }
                .associate { it.name to it.toMap() },
            "build-dependencies" to dependencies.filter { it.scope == DependencyScope.Build }
                .associate { it.name to it.toMap() },
            "dev-dependencies" to dependencies.filter { it.scope == DependencyScope.Dev }
                .associate { it.name to it.toMap() },
            "target.'cfg(aws_sdk_unstable)'.dependencies" to dependencies.filter {
                it.scope == DependencyScope.CfgUnstable
            }
                .associate { it.name to it.toMap() },
            "features" to cargoFeatures.toMap(),
        ).deepMergeWith(manifestCustomizations)

        // NOTE: without this it will produce ["target.'cfg(aws_sdk_unstable)'.dependencies"]
        // In JSON, this is an equivalent of: {"target.'cfg(aws_sdk_unstable)'.dependencies" : ...}
        // To make it work, it has to be: {"target": {'cfg(aws_sdk_unstable)': {"dependencies": ...}}}
        // This piece of code fixes it.
        var tomlString = TomlWriter().write(cargoToml).replace("\"target.'cfg(aws_sdk_unstable)'.dependencies\"", "target.'cfg(aws_sdk_unstable)'.dependencies")
        writer.writeWithNoFormatting(tomlString)
    }
}
