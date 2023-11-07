/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.generators.config

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.ClientRustModule
import software.amazon.smithy.rust.codegen.client.testutil.testClientCodegenContext
import software.amazon.smithy.rust.codegen.client.testutil.withEnableUserConfigurableRuntimePlugins
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.customize.NamedCustomization
import software.amazon.smithy.rust.codegen.core.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.core.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.core.testutil.unitTest
import software.amazon.smithy.rust.codegen.core.util.lookup
import software.amazon.smithy.rust.codegen.core.util.toPascalCase

internal class ServiceConfigGeneratorTest {
    @Test
    fun `idempotency token when used`() {
        fun model(trait: String) = """
            namespace com.example

            use aws.protocols#restJson1
            use smithy.test#httpRequestTests
            use smithy.test#httpResponseTests

            @restJson1
            service HelloService {
                operations: [SayHello],
                version: "1"
            }

            operation SayHello {
                input: IdempotentInput
            }

            structure IdempotentInput {
                $trait
                tok: String
            }
        """.asSmithyModel()

        val withToken = model("@idempotencyToken")
        val withoutToken = model("")
        withToken.lookup<ServiceShape>("com.example#HelloService").needsIdempotencyToken(withToken) shouldBe true
        withoutToken.lookup<ServiceShape>("com.example#HelloService").needsIdempotencyToken(withoutToken) shouldBe false
    }

    @Test
    fun `find idempotency token via resources`() {
        val model = """
            namespace com.example
            service ResourceService {
                resources: [Resource],
                version: "1"
            }

            resource Resource {
                operations: [CreateResource]
            }
            operation CreateResource {
                input: IdempotentInput
            }

            structure IdempotentInput {
                @idempotencyToken
                tok: String
            }
        """.asSmithyModel()
        model.lookup<ServiceShape>("com.example#ResourceService").needsIdempotencyToken(model) shouldBe true
    }

    @Test
    fun `generate customizations as specified`() {
        class ServiceCustomizer(private val codegenContext: ClientCodegenContext) :
            NamedCustomization<ServiceConfig>() {

            override fun section(section: ServiceConfig): Writable {
                return when (section) {
                    ServiceConfig.ConfigStructAdditionalDocs -> emptySection

                    ServiceConfig.ConfigImpl -> writable {
                        rustTemplate(
                            """
                            ##[allow(missing_docs)]
                            pub fn config_field(&self) -> u64 {
                                self.config.load::<#{T}>().map(|u| u.0).unwrap()
                            }
                            """,
                            "T" to configParamNewtype(
                                "config_field".toPascalCase(), RuntimeType.U64.toSymbol(),
                                codegenContext.runtimeConfig,
                            ),
                        )
                    }

                    ServiceConfig.BuilderImpl -> writable {
                        rustTemplate(
                            """
                            ##[allow(missing_docs)]
                            pub fn config_field(mut self, config_field: u64) -> Self {
                                self.config.store_put(#{T}(config_field));
                                self
                            }
                            """,
                            "T" to configParamNewtype(
                                "config_field".toPascalCase(), RuntimeType.U64.toSymbol(),
                                codegenContext.runtimeConfig,
                            ),
                        )
                    }

                    else -> emptySection
                }
            }
        }

        val model = "namespace empty".asSmithyModel()
        val codegenContext = testClientCodegenContext(model)
            .withEnableUserConfigurableRuntimePlugins(true)
        val sut = ServiceConfigGenerator(codegenContext, listOf(ServiceCustomizer(codegenContext)))
        val symbolProvider = codegenContext.symbolProvider
        val project = TestWorkspace.testProject(symbolProvider)
        project.withModule(ClientRustModule.config) {
            sut.render(this)
            unitTest(
                "set_config_fields",
                """
                let builder = Config::builder().config_field(99);
                let config = builder.build();
                assert_eq!(config.config_field(), 99);
                """,
            )

            unitTest(
                "set_runtime_plugin",
                """
                use aws_smithy_runtime_api::client::runtime_plugin::RuntimePlugin;
                use aws_smithy_types::config_bag::FrozenLayer;

                #[derive(Debug)]
                struct TestRuntimePlugin;

                impl RuntimePlugin for TestRuntimePlugin {
                    fn config(&self) -> Option<FrozenLayer> {
                        todo!("ExampleRuntimePlugin.config")
                    }
                }

                let config = Config::builder()
                    .runtime_plugin(TestRuntimePlugin)
                    .build();
                assert_eq!(config.runtime_plugins.len(), 1);
                """,
            )
        }
        project.compileAndTest()
    }
}
