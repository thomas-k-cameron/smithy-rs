/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk.endpoints

import software.amazon.smithy.model.node.Node
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.rulesengine.language.syntax.parameters.Builtins
import software.amazon.smithy.rulesengine.traits.EndpointTestCase
import software.amazon.smithy.rulesengine.traits.EndpointTestOperationInput
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.customize.ClientCodegenDecorator
import software.amazon.smithy.rust.codegen.client.smithy.endpoint.EndpointTypesGenerator
import software.amazon.smithy.rust.codegen.client.smithy.generators.ClientInstantiator
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.AttributeKind
import software.amazon.smithy.rust.codegen.core.rustlang.escape
import software.amazon.smithy.rust.codegen.core.rustlang.join
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.PublicImportSymbolProvider
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.smithy.generators.setterName
import software.amazon.smithy.rust.codegen.core.testutil.integrationTest
import software.amazon.smithy.rust.codegen.core.testutil.tokioTest
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.expectMember
import software.amazon.smithy.rust.codegen.core.util.inputShape
import software.amazon.smithy.rust.codegen.core.util.orNull
import software.amazon.smithy.rust.codegen.core.util.orNullIfEmpty
import software.amazon.smithy.rust.codegen.core.util.toSnakeCase
import java.util.logging.Logger

class OperationInputTestDecorator : ClientCodegenDecorator {
    override val name: String = "OperationInputTest"
    override val order: Byte = 0

    override fun extras(codegenContext: ClientCodegenContext, rustCrate: RustCrate) {
        val endpointTests = EndpointTypesGenerator.fromContext(codegenContext).tests.orNullIfEmpty() ?: return
        rustCrate.integrationTest("endpoint_tests") {
            Attribute(Attribute.cfg(Attribute.feature("test-util"))).render(this, AttributeKind.Inner)
            val tests = endpointTests.flatMap { test ->
                val generator = OperationInputTestGenerator(codegenContext, test)
                test.operationInputs.filterNot { usesDeprecatedBuiltIns(it) }.map { operationInput ->
                    generator.generateInput(operationInput)
                }
            }
            tests.join("\n")(this)
        }
    }
}

private val deprecatedBuiltins =
    setOf(
        // The Rust SDK DOES NOT support the S3 global endpoint because we do not support bucket redirects
        Builtins.S3_USE_GLOBAL_ENDPOINT,
        // STS global endpoint was deprecated after STS regionalization
        Builtins.STS_USE_GLOBAL_ENDPOINT,
    ).map { it.builtIn.get() }

fun usesDeprecatedBuiltIns(testOperationInput: EndpointTestOperationInput): Boolean {
    return testOperationInput.builtInParams.members.map { it.key.value }.any { deprecatedBuiltins.contains(it) }
}

/**
 * Generate `operationInputTests` for EP2 tests.
 *
 * These are `tests/` style integration tests that run as a public SDK user against a complete client. `capture_request`
 * is used to retrieve the URL.
 *
 * Example generated test:
 * ```rust
 * #[tokio::test]
 * async fn operation_input_test_get_object_119() {
 *     /* builtIns: {
 *         "AWS::Region": "us-west-2",
 *         "AWS::S3::UseArnRegion": false
 *     } */
 *     /* clientParams: {} */
 *     let (conn, rcvr) = aws_smithy_client::test_connection::capture_request(None);
 *     let conf = {
 *         #[allow(unused_mut)]
 *         let mut builder = aws_sdk_s3::Config::builder()
 *             .with_test_defaults()
 *             .http_connector(conn);
 *         let builder = builder.region(aws_types::region::Region::new("us-west-2"));
 *         let builder = builder.use_arn_region(false);
 *         builder.build()
 *     };
 *     let client = aws_sdk_s3::Client::from_conf(conf);
 *     let _result = dbg!(client.get_object()
 *         .set_bucket(Some(
 *             "arn:aws:s3-outposts:us-east-1:123456789012:outpost:op-01234567890123456:accesspoint:myaccesspoint".to_owned()
 *         ))
 *         .set_key(Some(
 *             "key".to_owned()
 *         ))
 *         .send().await);
 *     rcvr.expect_no_request();
 *     let error = _result.expect_err("expected error: Invalid configuration: region from ARN `us-east-1` does not match client region `us-west-2` and UseArnRegion is `false` [outposts arn with region mismatch and UseArnRegion=false]");
 *     assert!(format!("{:?}", error).contains("Invalid configuration: region from ARN `us-east-1` does not match client region `us-west-2` and UseArnRegion is `false`"), "expected error to contain `Invalid configuration: region from ARN `us-east-1` does not match client region `us-west-2` and UseArnRegion is `false`` but it was {}", format!("{:?}", error));
 * }
 * ```
 *
 * Eventually, we need to pull this test into generic smithy. However, this relies on generic smithy clients
 * supporting middleware and being instantiable from config (https://github.com/awslabs/smithy-rs/issues/2194)
 *
 * Doing this in AWS codegen allows us to actually integration test generated clients.
 */

class OperationInputTestGenerator(_ctx: ClientCodegenContext, private val test: EndpointTestCase) {
    private val ctx = _ctx.copy(symbolProvider = PublicImportSymbolProvider(_ctx.symbolProvider, _ctx.moduleUseName()))
    private val runtimeConfig = ctx.runtimeConfig
    private val moduleName = ctx.moduleUseName()
    private val endpointCustomizations = ctx.rootDecorator.endpointCustomizations(ctx)
    private val model = ctx.model
    private val instantiator = ClientInstantiator(ctx)

    /** the Rust SDK doesn't support SigV4a — search  endpoint.properties.authSchemes[].name */
    private fun EndpointTestCase.isSigV4a() =
        expect.endpoint.orNull()?.properties?.get("authSchemes")?.asArrayNode()?.orNull()
            ?.map { it.expectObjectNode().expectStringMember("name").value }?.contains("sigv4a") == true

    fun generateInput(testOperationInput: EndpointTestOperationInput) = writable {
        val operationName = testOperationInput.operationName.toSnakeCase()
        if (test.isSigV4a()) {
            Attribute.shouldPanic("no request was received").render(this)
        }
        tokioTest(safeName("operation_input_test_$operationName")) {
            rustTemplate(
                """
                /* builtIns: ${escape(Node.prettyPrintJson(testOperationInput.builtInParams))} */
                /* clientParams: ${escape(Node.prettyPrintJson(testOperationInput.clientParams))} */
                let (conn, rcvr) = #{capture_request}(None);
                let conf = #{conf};
                let client = $moduleName::Client::from_conf(conf);
                let _result = dbg!(#{invoke_operation});
                #{assertion}
                """,
                "capture_request" to RuntimeType.captureRequest(runtimeConfig),
                "conf" to config(testOperationInput),
                "invoke_operation" to operationInvocation(testOperationInput),
                "assertion" to writable {
                    test.expect.endpoint.ifPresent { endpoint ->
                        val uri = escape(endpoint.url)
                        rustTemplate(
                            """
                            let req = rcvr.expect_request();
                            let uri = req.uri().to_string();
                            assert!(uri.starts_with(${uri.dq()}), "expected URI to start with `$uri` but it was `{}`", uri);
                            """,
                        )
                    }
                    test.expect.error.ifPresent { error ->
                        val expectedError =
                            escape("expected error: $error [${test.documentation.orNull() ?: "no docs"}]")
                        val escapedError = escape(error)
                        rustTemplate(
                            """
                            rcvr.expect_no_request();
                            let error = _result.expect_err(${expectedError.dq()});
                            assert!(
                                format!("{:?}", error).contains(${escapedError.dq()}),
                                "expected error to contain `$escapedError` but it was {:?}", error
                            );
                            """,
                        )
                    }
                },
            )
        }
    }

    private fun operationInvocation(testOperationInput: EndpointTestOperationInput) = writable {
        rust("client.${testOperationInput.operationName.toSnakeCase()}()")
        val operationInput =
            model.expectShape(ctx.operationId(testOperationInput), OperationShape::class.java).inputShape(model)
        testOperationInput.operationParams.members.forEach { (key, value) ->
            val member = operationInput.expectMember(key.value)
            rustTemplate(
                ".${member.setterName()}(#{value})",
                "value" to instantiator.generate(member, value),
            )
        }
        rust(".send().await")
    }

    /** initialize service config for test */
    private fun config(operationInput: EndpointTestOperationInput) = writable {
        rustBlock("") {
            Attribute.AllowUnusedMut.render(this)
            rust("let mut builder = $moduleName::Config::builder().with_test_defaults().http_connector(conn);")
            operationInput.builtInParams.members.forEach { (builtIn, value) ->
                val setter = endpointCustomizations.firstNotNullOfOrNull {
                    it.setBuiltInOnServiceConfig(
                        builtIn.value,
                        value,
                        "builder",
                    )
                }
                if (setter != null) {
                    setter(this)
                } else {
                    Logger.getLogger("OperationTestGenerator").warning("No provider for ${builtIn.value}")
                }
            }
            rust("builder.build()")
        }
    }
}

fun ClientCodegenContext.operationId(testOperationInput: EndpointTestOperationInput): ShapeId =
    this.serviceShape.allOperations.first { it.name == testOperationInput.operationName }
