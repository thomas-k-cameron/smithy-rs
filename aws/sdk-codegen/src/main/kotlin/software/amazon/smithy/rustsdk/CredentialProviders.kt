/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk

import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.ClientRustModule
import software.amazon.smithy.rust.codegen.client.smithy.customize.ClientCodegenDecorator
import software.amazon.smithy.rust.codegen.client.smithy.customize.TestUtilFeature
import software.amazon.smithy.rust.codegen.client.smithy.endpoint.supportedAuthSchemes
import software.amazon.smithy.rust.codegen.client.smithy.generators.ServiceRuntimePluginCustomization
import software.amazon.smithy.rust.codegen.client.smithy.generators.ServiceRuntimePluginSection
import software.amazon.smithy.rust.codegen.client.smithy.generators.config.ConfigCustomization
import software.amazon.smithy.rust.codegen.client.smithy.generators.config.ServiceConfig
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.featureGateBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType.Companion.preludeScope
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.smithy.customize.AdHocCustomization
import software.amazon.smithy.rust.codegen.core.smithy.customize.adhocCustomization

class CredentialsProviderDecorator : ClientCodegenDecorator {
    override val name: String = "CredentialsProvider"
    override val order: Byte = 0

    override fun serviceRuntimePluginCustomizations(
        codegenContext: ClientCodegenContext,
        baseCustomizations: List<ServiceRuntimePluginCustomization>,
    ): List<ServiceRuntimePluginCustomization> =
        baseCustomizations + listOf(CredentialsIdentityResolverRegistration(codegenContext))

    override fun configCustomizations(
        codegenContext: ClientCodegenContext,
        baseCustomizations: List<ConfigCustomization>,
    ): List<ConfigCustomization> {
        return baseCustomizations + CredentialProviderConfig(codegenContext)
    }

    override fun extraSections(codegenContext: ClientCodegenContext): List<AdHocCustomization> =
        listOf(
            adhocCustomization<SdkConfigSection.CopySdkConfigToClientConfig> { section ->
                rust("${section.serviceConfigBuilder}.set_credentials_provider(${section.sdkConfig}.credentials_provider());")
            },
        )

    override fun extras(codegenContext: ClientCodegenContext, rustCrate: RustCrate) {
        rustCrate.mergeFeature(TestUtilFeature.copy(deps = listOf("aws-credential-types/test-util")))

        rustCrate.withModule(ClientRustModule.config) {
            rust(
                "pub use #T::Credentials;",
                AwsRuntimeType.awsCredentialTypes(codegenContext.runtimeConfig),
            )
        }
    }
}

/**
 * Add a `.credentials_provider` field and builder to the `Config` for a given service
 */
class CredentialProviderConfig(codegenContext: ClientCodegenContext) : ConfigCustomization() {
    private val runtimeConfig = codegenContext.runtimeConfig
    private val codegenScope = arrayOf(
        *preludeScope,
        "Credentials" to AwsRuntimeType.awsCredentialTypes(runtimeConfig).resolve("Credentials"),
        "ProvideCredentials" to AwsRuntimeType.awsCredentialTypes(runtimeConfig)
            .resolve("provider::ProvideCredentials"),
        "SharedCredentialsProvider" to AwsRuntimeType.awsCredentialTypes(runtimeConfig)
            .resolve("provider::SharedCredentialsProvider"),
        "TestCredentials" to AwsRuntimeType.awsCredentialTypesTestUtil(runtimeConfig).resolve("Credentials"),
    )

    override fun section(section: ServiceConfig) = writable {
        when (section) {
            ServiceConfig.ConfigImpl -> {
                rustTemplate(
                    """
                    /// Returns the credentials provider for this service
                    pub fn credentials_provider(&self) -> Option<#{SharedCredentialsProvider}> {
                        self.config.load::<#{SharedCredentialsProvider}>().cloned()
                    }
                    """,
                    *codegenScope,
                )
            }

            ServiceConfig.BuilderImpl -> {
                rustTemplate(
                    """
                    /// Sets the credentials provider for this service
                    pub fn credentials_provider(mut self, credentials_provider: impl #{ProvideCredentials} + 'static) -> Self {
                        self.set_credentials_provider(#{Some}(#{SharedCredentialsProvider}::new(credentials_provider)));
                        self
                    }
                    """,
                    *codegenScope,
                )

                rustTemplate(
                    """
                    /// Sets the credentials provider for this service
                    pub fn set_credentials_provider(&mut self, credentials_provider: #{Option}<#{SharedCredentialsProvider}>) -> &mut Self {
                        self.config.store_or_unset(credentials_provider);
                        self
                    }
                    """,
                    *codegenScope,
                )
            }

            is ServiceConfig.DefaultForTests -> rustTemplate(
                "${section.configBuilderRef}.set_credentials_provider(Some(#{SharedCredentialsProvider}::new(#{TestCredentials}::for_tests())));",
                *codegenScope,
            )

            else -> emptySection
        }
    }
}

class CredentialsIdentityResolverRegistration(
    private val codegenContext: ClientCodegenContext,
) : ServiceRuntimePluginCustomization() {
    private val runtimeConfig = codegenContext.runtimeConfig

    override fun section(section: ServiceRuntimePluginSection): Writable = writable {
        when (section) {
            is ServiceRuntimePluginSection.RegisterRuntimeComponents -> {
                rustBlockTemplate("if let Some(creds_provider) = ${section.serviceConfigName}.credentials_provider()") {
                    val codegenScope = arrayOf(
                        "SharedIdentityResolver" to RuntimeType.smithyRuntimeApi(runtimeConfig)
                            .resolve("client::identity::SharedIdentityResolver"),
                        "SIGV4A_SCHEME_ID" to AwsRuntimeType.awsRuntime(runtimeConfig)
                            .resolve("auth::sigv4a::SCHEME_ID"),
                        "SIGV4_SCHEME_ID" to AwsRuntimeType.awsRuntime(runtimeConfig)
                            .resolve("auth::sigv4::SCHEME_ID"),
                    )

                    if (codegenContext.serviceShape.supportedAuthSchemes().contains("sigv4a")) {
                        featureGateBlock("sigv4a") {
                            section.registerIdentityResolver(this) {
                                rustTemplate("#{SIGV4A_SCHEME_ID}, creds_provider.clone()", *codegenScope)
                            }
                        }
                    }
                    section.registerIdentityResolver(this) {
                        rustTemplate("#{SIGV4_SCHEME_ID}, creds_provider,", *codegenScope)
                    }
                }
            }

            else -> {}
        }
    }
}
