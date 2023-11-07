/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.NullableIndex
import software.amazon.smithy.model.node.ObjectNode
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.rust.codegen.core.smithy.CODEGEN_SETTINGS
import software.amazon.smithy.rust.codegen.core.smithy.CoreCodegenConfig
import software.amazon.smithy.rust.codegen.core.smithy.CoreRustSettings
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.core.util.orNull
import java.util.Optional

/**
 * [ClientRustSettings] and [ClientCodegenConfig] classes.
 *
 * These are specializations of [CoreRustSettings] and [CodegenConfig] for the `rust-client-codegen`
 * client Smithy plugin. Refer to the documentation of those for the inherited properties.
 */

/**
 * Settings used by [RustClientCodegenPlugin].
 */
data class ClientRustSettings(
    override val service: ShapeId,
    override val moduleName: String,
    override val moduleVersion: String,
    override val moduleAuthors: List<String>,
    override val moduleDescription: String?,
    override val moduleRepository: String?,
    override val runtimeConfig: RuntimeConfig,
    override val codegenConfig: ClientCodegenConfig,
    override val license: String?,
    override val examplesUri: String?,
    override val customizationConfig: ObjectNode?,
) : CoreRustSettings(
    service,
    moduleName,
    moduleVersion,
    moduleAuthors,
    moduleDescription,
    moduleRepository,
    runtimeConfig,
    codegenConfig,
    license,
    examplesUri,
    customizationConfig,
) {
    companion object {
        fun from(model: Model, config: ObjectNode): ClientRustSettings {
            val coreRustSettings = CoreRustSettings.from(model, config)
            val codegenSettingsNode = config.getObjectMember(CODEGEN_SETTINGS)
            val coreCodegenConfig = CoreCodegenConfig.fromNode(codegenSettingsNode)
            return ClientRustSettings(
                service = coreRustSettings.service,
                moduleName = coreRustSettings.moduleName,
                moduleVersion = coreRustSettings.moduleVersion,
                moduleAuthors = coreRustSettings.moduleAuthors,
                moduleDescription = coreRustSettings.moduleDescription,
                moduleRepository = coreRustSettings.moduleRepository,
                runtimeConfig = coreRustSettings.runtimeConfig,
                codegenConfig = ClientCodegenConfig.fromCodegenConfigAndNode(coreCodegenConfig, codegenSettingsNode),
                license = coreRustSettings.license,
                examplesUri = coreRustSettings.examplesUri,
                customizationConfig = coreRustSettings.customizationConfig,
            )
        }
    }
}

/**
 * [renameExceptions]: Rename `Exception` to `Error` in the generated SDK
 * [includeFluentClient]: Generate a `client` module in the generated SDK (currently the AWS SDK sets this to `false`
 *   and generates its own client)
 * [addMessageToErrors]: Adds a `message` field automatically to all error shapes
 */
data class ClientCodegenConfig(
    override val formatTimeoutSeconds: Int = defaultFormatTimeoutSeconds,
    override val debugMode: Boolean = defaultDebugMode,
    override val flattenCollectionAccessors: Boolean = defaultFlattenAccessors,
    val nullabilityCheckMode: NullableIndex.CheckMode = NullableIndex.CheckMode.CLIENT,
    val renameExceptions: Boolean = defaultRenameExceptions,
    val includeFluentClient: Boolean = defaultIncludeFluentClient,
    val addMessageToErrors: Boolean = defaultAddMessageToErrors,
    // TODO(EventStream): [CLEANUP] Remove this property when turning on Event Stream for all services
    val eventStreamAllowList: Set<String> = defaultEventStreamAllowList,
    /** If true, adds `endpoint_url`/`set_endpoint_url` methods to the service config */
    val includeEndpointUrlConfig: Boolean = defaultIncludeEndpointUrlConfig,
    val enableUserConfigurableRuntimePlugins: Boolean = defaultEnableUserConfigurableRuntimePlugins,
) : CoreCodegenConfig(
    formatTimeoutSeconds, debugMode, defaultFlattenAccessors,
) {
    companion object {
        private const val defaultRenameExceptions = true
        private const val defaultIncludeFluentClient = true
        private const val defaultAddMessageToErrors = true
        private val defaultEventStreamAllowList: Set<String> = emptySet()
        private const val defaultIncludeEndpointUrlConfig = true
        private const val defaultEnableUserConfigurableRuntimePlugins = true
        private const val defaultNullabilityCheckMode = "CLIENT"

        // Note: only clients default to true, servers default to false
        private const val defaultFlattenAccessors = true

        fun fromCodegenConfigAndNode(coreCodegenConfig: CoreCodegenConfig, node: Optional<ObjectNode>) =
            if (node.isPresent) {
                ClientCodegenConfig(
                    formatTimeoutSeconds = coreCodegenConfig.formatTimeoutSeconds,
                    flattenCollectionAccessors = node.get().getBooleanMemberOrDefault("flattenCollectionAccessors", defaultFlattenAccessors),
                    debugMode = coreCodegenConfig.debugMode,
                    eventStreamAllowList = node.get().getArrayMember("eventStreamAllowList").map { array ->
                        array.toList().mapNotNull { node ->
                            node.asStringNode().orNull()?.value
                        }
                    }.orNull()?.toSet() ?: defaultEventStreamAllowList,
                    renameExceptions = node.get().getBooleanMemberOrDefault("renameErrors", defaultRenameExceptions),
                    includeFluentClient = node.get().getBooleanMemberOrDefault("includeFluentClient", defaultIncludeFluentClient),
                    addMessageToErrors = node.get().getBooleanMemberOrDefault("addMessageToErrors", defaultAddMessageToErrors),
                    includeEndpointUrlConfig = node.get().getBooleanMemberOrDefault("includeEndpointUrlConfig", defaultIncludeEndpointUrlConfig),
                    enableUserConfigurableRuntimePlugins = node.get().getBooleanMemberOrDefault("enableUserConfigurableRuntimePlugins", defaultEnableUserConfigurableRuntimePlugins),
                    nullabilityCheckMode = NullableIndex.CheckMode.valueOf(node.get().getStringMemberOrDefault("nullabilityCheckMode", defaultNullabilityCheckMode)),
                )
            } else {
                ClientCodegenConfig(
                    formatTimeoutSeconds = coreCodegenConfig.formatTimeoutSeconds,
                    debugMode = coreCodegenConfig.debugMode,
                    nullabilityCheckMode = NullableIndex.CheckMode.valueOf(defaultNullabilityCheckMode),
                )
            }
    }
}
