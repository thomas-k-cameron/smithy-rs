/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.sdk

import PropertyRetriever
import org.gradle.api.Project
import org.slf4j.LoggerFactory
import java.io.File
import java.security.MessageDigest

const val LOCAL_DEV_VERSION: String = "0.0.0-local"

object CrateVersioner {
    fun defaultFor(rootProject: Project, properties: PropertyRetriever): VersionCrate =
        when (val versionsManifestPath = properties.get("aws.sdk.previous.release.versions.manifest")) {
            // In local dev, use special `0.0.0-local` version number for all SDK crates
            null -> SynchronizedCrateVersioner(properties, sdkVersion = LOCAL_DEV_VERSION)
            else -> {
                val modelMetadataPath = properties.get("aws.sdk.model.metadata")
                    ?: throw IllegalArgumentException("Property `aws.sdk.model.metadata` required for independent crate version builds")
                IndependentCrateVersioner(
                    VersionsManifest.fromFile(versionsManifestPath),
                    ModelMetadata.fromFile(modelMetadataPath),
                    devPreview = false,
                    smithyRsVersion = getSmithyRsVersion(rootProject),
                )
            }
        }
}

interface VersionCrate {
    fun decideCrateVersion(moduleName: String, service: AwsService): String

    fun independentVersioningEnabled(): Boolean
}

class SynchronizedCrateVersioner(
    properties: PropertyRetriever,
    private val sdkVersion: String = properties.get(CrateSet.STABLE_VERSION_PROP_NAME)
        ?: throw Exception("SDK runtime crate version missing"),
) : VersionCrate {
    init {
        LoggerFactory.getLogger(javaClass).info("Using synchronized SDK crate versioning with version `$sdkVersion`")
    }

    override fun decideCrateVersion(moduleName: String, service: AwsService): String = sdkVersion

    override fun independentVersioningEnabled(): Boolean = sdkVersion == LOCAL_DEV_VERSION
}

private data class SemVer(
    val major: Int,
    val minor: Int,
    val patch: Int,
) {
    companion object {
        fun parse(value: String): SemVer {
            val parseNote = "Note: This implementation doesn't implement pre-release/build version support"
            val failure = IllegalArgumentException("Unrecognized semver version number: $value. $parseNote")
            val parts = value.split(".")
            if (parts.size != 3) {
                throw failure
            }
            return SemVer(
                major = parts[0].toIntOrNull() ?: throw failure,
                minor = parts[1].toIntOrNull() ?: throw failure,
                patch = parts[2].toIntOrNull() ?: throw failure,
            )
        }
    }

    fun bumpMajor(): SemVer = copy(major = major + 1, minor = 0, patch = 0)
    fun bumpMinor(): SemVer = copy(minor = minor + 1, patch = 0)
    fun bumpPatch(): SemVer = copy(patch = patch + 1)

    override fun toString(): String {
        return "$major.$minor.$patch"
    }
}

fun getSmithyRsVersion(rootProject: Project): String {
    Runtime.getRuntime().let { runtime ->
        val command = arrayOf("git", "-C", rootProject.rootDir.absolutePath, "rev-parse", "HEAD")
        val process = runtime.exec(command)
        if (process.waitFor() != 0) {
            throw RuntimeException(
                "Failed to run `${command.joinToString(" ")}`:\n" +
                    "stdout: " +
                    String(process.inputStream.readAllBytes()) +
                    "stderr: " +
                    String(process.errorStream.readAllBytes()),
            )
        }
        return String(process.inputStream.readAllBytes()).trim()
    }
}

class IndependentCrateVersioner(
    private val versionsManifest: VersionsManifest,
    private val modelMetadata: ModelMetadata,
    private val devPreview: Boolean,
    smithyRsVersion: String,
    private val hashModelsFn: (AwsService) -> String = { service -> hashModels(service) },
) : VersionCrate {
    private val smithyRsChanged = versionsManifest.smithyRsRevision != smithyRsVersion
    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        logger.info("Using independent SDK crate versioning. Dev preview: $devPreview")
        logger.info(
            "Current smithy-rs HEAD: `$smithyRsVersion`. " +
                "Previous smithy-rs HEAD from versions.toml: `${versionsManifest.smithyRsRevision}`. " +
                "Code generator changed: $smithyRsChanged",
        )
    }

    override fun independentVersioningEnabled(): Boolean = true

    override fun decideCrateVersion(moduleName: String, service: AwsService): String {
        var previousVersion: SemVer? = null
        val (reason, newVersion) = when (val existingCrate = versionsManifest.crates.get(moduleName)) {
            // The crate didn't exist before, so create a new major version
            null -> "new service" to newMajorVersion()
            else -> {
                previousVersion = SemVer.parse(existingCrate.version)
                if (smithyRsChanged) {
                    "smithy-rs changed" to previousVersion.bumpCodegenChanged()
                } else {
                    when (modelMetadata.changeType(moduleName)) {
                        ChangeType.FEATURE -> "its API changed" to previousVersion.bumpModelChanged()
                        ChangeType.DOCUMENTATION -> "it has new docs" to previousVersion.bumpDocsChanged()
                        ChangeType.UNCHANGED -> {
                            val currentModelsHash = hashModelsFn(service)
                            val previousModelsHash = existingCrate.modelHash
                            if (currentModelsHash != previousModelsHash) {
                                "its model(s) changed" to previousVersion.bumpModelChanged()
                            } else {
                                "no change" to previousVersion
                            }
                        }
                    }
                }
            }
        }
        if (previousVersion == null) {
            logger.info("`$moduleName` is a new service. Starting it at `$newVersion`")
        } else if (previousVersion != newVersion) {
            logger.info("Version bumping `$moduleName` from `$previousVersion` to `$newVersion` because $reason")
        } else {
            logger.info("No changes expected for `$moduleName`")
        }
        return newVersion.toString()
    }

    private fun newMajorVersion(): SemVer = when (devPreview) {
        true -> SemVer.parse("0.1.0")
        else -> SemVer.parse("1.0.0")
    }

    private fun SemVer.bumpCodegenChanged(): SemVer = bumpMinor()
    private fun SemVer.bumpModelChanged(): SemVer = when (devPreview) {
        true -> bumpPatch()
        else -> bumpMinor()
    }

    private fun SemVer.bumpDocsChanged(): SemVer = bumpPatch()
}

private fun ByteArray.toLowerHex(): String = joinToString("") { byte -> "%02x".format(byte) }

fun hashModels(awsService: AwsService, loadFile: (File) -> ByteArray = File::readBytes): String {
    // Needs to match hashing done in the `generate-version-manifest` tool:
    val sha256 = MessageDigest.getInstance("SHA-256")
    val hashes = awsService.modelFiles().fold("") { hashes, file ->
        val fileHash = sha256.digest(loadFile(file)).toLowerHex()
        hashes + fileHash + "\n"
    }
    return sha256.digest(hashes.toByteArray(Charsets.UTF_8)).toLowerHex()
}
