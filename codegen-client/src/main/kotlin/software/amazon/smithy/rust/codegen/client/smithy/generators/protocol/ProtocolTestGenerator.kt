/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.generators.protocol

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.model.knowledge.OperationIndex
import software.amazon.smithy.model.shapes.DoubleShape
import software.amazon.smithy.model.shapes.FloatShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.model.traits.IdempotencyTokenTrait
import software.amazon.smithy.protocoltests.traits.AppliesTo
import software.amazon.smithy.protocoltests.traits.HttpMessageTestCase
import software.amazon.smithy.protocoltests.traits.HttpRequestTestCase
import software.amazon.smithy.protocoltests.traits.HttpRequestTestsTrait
import software.amazon.smithy.protocoltests.traits.HttpResponseTestCase
import software.amazon.smithy.protocoltests.traits.HttpResponseTestsTrait
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.generators.clientInstantiator
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.RustMetadata
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Visibility
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.escape
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.withBlock
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.generators.error.errorSymbol
import software.amazon.smithy.rust.codegen.core.smithy.generators.protocol.ProtocolSupport
import software.amazon.smithy.rust.codegen.core.testutil.TokioTest
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.findMemberWithTrait
import software.amazon.smithy.rust.codegen.core.util.getTrait
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.inputShape
import software.amazon.smithy.rust.codegen.core.util.isStreaming
import software.amazon.smithy.rust.codegen.core.util.orNull
import software.amazon.smithy.rust.codegen.core.util.outputShape
import software.amazon.smithy.rust.codegen.core.util.toSnakeCase
import java.util.logging.Logger

/**
 * Generate protocol tests for an operation
 */
class ProtocolTestGenerator(
    private val codegenContext: ClientCodegenContext,
    private val protocolSupport: ProtocolSupport,
    private val operationShape: OperationShape,
    private val writer: RustWriter,
) {
    private val logger = Logger.getLogger(javaClass.name)

    private val inputShape = operationShape.inputShape(codegenContext.model)
    private val outputShape = operationShape.outputShape(codegenContext.model)
    private val operationSymbol = codegenContext.symbolProvider.toSymbol(operationShape)
    private val operationIndex = OperationIndex.of(codegenContext.model)

    private val instantiator = clientInstantiator(codegenContext)

    private val codegenScope = arrayOf(
        "SmithyHttp" to RuntimeType.smithyHttp(codegenContext.runtimeConfig),
        "AssertEq" to RuntimeType.PrettyAssertions.resolve("assert_eq!"),
    )

    sealed class TestCase {
        abstract val testCase: HttpMessageTestCase

        data class RequestTest(override val testCase: HttpRequestTestCase) : TestCase()
        data class ResponseTest(override val testCase: HttpResponseTestCase, val targetShape: StructureShape) :
            TestCase()
    }

    fun render() {
        val requestTests = operationShape.getTrait<HttpRequestTestsTrait>()
            ?.getTestCasesFor(AppliesTo.CLIENT).orEmpty().map { TestCase.RequestTest(it) }
        val responseTests = operationShape.getTrait<HttpResponseTestsTrait>()
            ?.getTestCasesFor(AppliesTo.CLIENT).orEmpty().map { TestCase.ResponseTest(it, outputShape) }
        val errorTests = operationIndex.getErrors(operationShape).flatMap { error ->
            val testCases = error.getTrait<HttpResponseTestsTrait>()
                ?.getTestCasesFor(AppliesTo.CLIENT).orEmpty()
            testCases.map { TestCase.ResponseTest(it, error) }
        }
        val allTests: List<TestCase> = (requestTests + responseTests + errorTests).filterMatching()
        if (allTests.isNotEmpty()) {
            val operationName = operationSymbol.name
            val testModuleName = "${operationName.toSnakeCase()}_request_test"
            val moduleMeta = RustMetadata(
                visibility = Visibility.PRIVATE,
                additionalAttributes = listOf(
                    Attribute.Cfg("test"),
                    Attribute.Custom("allow(unreachable_code, unused_variables)"),
                ),
            )
            writer.withInlineModule(RustModule.LeafModule(testModuleName, moduleMeta, inline = true)) {
                renderAllTestCases(allTests)
            }
        }
    }

    private fun RustWriter.renderAllTestCases(allTests: List<TestCase>) {
        allTests.forEach {
            renderTestCaseBlock(it.testCase, this) {
                when (it) {
                    is TestCase.RequestTest -> this.renderHttpRequestTestCase(it.testCase)
                    is TestCase.ResponseTest -> this.renderHttpResponseTestCase(it.testCase, it.targetShape)
                }
            }
        }
    }

    /**
     * Filter out test cases that are disabled or don't match the service protocol
     */
    private fun List<TestCase>.filterMatching(): List<TestCase> {
        return if (RunOnly.isNullOrEmpty()) {
            this.filter { testCase ->
                testCase.testCase.protocol == codegenContext.protocol &&
                    !DisableTests.contains(testCase.testCase.id)
            }
        } else {
            this.filter { RunOnly.contains(it.testCase.id) }
        }
    }

    private fun renderTestCaseBlock(
        testCase: HttpMessageTestCase,
        testModuleWriter: RustWriter,
        block: Writable,
    ) {
        testModuleWriter.newlinePrefix = "/// "
        testCase.documentation.map {
            testModuleWriter.writeWithNoFormatting(it)
        }
        testModuleWriter.write("Test ID: ${testCase.id}")
        testModuleWriter.newlinePrefix = ""
        TokioTest.render(testModuleWriter)
        val action = when (testCase) {
            is HttpResponseTestCase -> Action.Response
            is HttpRequestTestCase -> Action.Request
            else -> throw CodegenException("unknown test case type")
        }
        if (expectFail(testCase)) {
            testModuleWriter.writeWithNoFormatting("#[should_panic]")
        }
        val fnName = when (action) {
            is Action.Response -> "_response"
            is Action.Request -> "_request"
        }
        testModuleWriter.rustBlock("async fn ${testCase.id.toSnakeCase()}$fnName()") {
            block(this)
        }
    }

    private fun RustWriter.renderHttpRequestTestCase(
        httpRequestTestCase: HttpRequestTestCase,
    ) {
        if (!protocolSupport.requestSerialization) {
            rust("/* test case disabled for this protocol (not yet supported) */")
            return
        }
        val customToken = if (inputShape.findMemberWithTrait<IdempotencyTokenTrait>(codegenContext.model) != null) {
            """.make_token("00000000-0000-4000-8000-000000000000")"""
        } else ""
        val customParams = httpRequestTestCase.vendorParams.getObjectMember("endpointParams").orNull()?.let { params ->
            writable {
                val customizations = codegenContext.rootDecorator.endpointCustomizations(codegenContext)
                params.getObjectMember("builtInParams").orNull()?.members?.forEach { (name, value) ->
                    customizations.firstNotNullOf { it.setBuiltInOnConfig(name.value, value, "builder") }(this)
                }
            }
        } ?: writable { }
        rustTemplate(
            """
            let builder = #{Config}::Config::builder()$customToken;
            #{customParams}
            let config = builder.build();

            """,
            "Config" to RuntimeType.Config,
            "customParams" to customParams,
        )
        writeInline("let input =")
        instantiator.render(this, inputShape, httpRequestTestCase.params)

        rust(""".make_operation(&config).await.expect("operation failed to build");""")
        rust("let (http_request, parts) = input.into_request_response().0.into_parts();")
        with(httpRequestTestCase) {
            host.orNull()?.also { host ->
                val withScheme = "http://$host"
                rustTemplate(
                    """
                    let mut http_request = http_request;
                    let ep = #{SmithyHttp}::endpoint::Endpoint::mutable(${withScheme.dq()}).expect("valid endpoint");
                    ep.set_endpoint(http_request.uri_mut(), parts.acquire().get()).expect("valid endpoint");
                    """,
                    *codegenScope,
                )
            }
            rustTemplate(
                """
                #{AssertEq}(http_request.method(), ${method.dq()});
                #{AssertEq}(http_request.uri().path(), ${uri.dq()});
                """,
                *codegenScope,
            )
            resolvedHost.orNull()?.also { host ->
                rustTemplate(
                    """#{AssertEq}(http_request.uri().host().expect("host should be set"), ${host.dq()});""",
                    *codegenScope,
                )
            }
        }
        checkQueryParams(this, httpRequestTestCase.queryParams)
        checkForbidQueryParams(this, httpRequestTestCase.forbidQueryParams)
        checkRequiredQueryParams(this, httpRequestTestCase.requireQueryParams)
        checkHeaders(this, "&http_request.headers()", httpRequestTestCase.headers)
        checkForbidHeaders(this, "&http_request.headers()", httpRequestTestCase.forbidHeaders)
        checkRequiredHeaders(this, "&http_request.headers()", httpRequestTestCase.requireHeaders)
        if (protocolSupport.requestBodySerialization) {
            // "If no request body is defined, then no assertions are made about the body of the message."
            httpRequestTestCase.body.orNull()?.also { body ->
                checkBody(this, body, httpRequestTestCase.bodyMediaType.orNull())
            }
        }

        // Explicitly warn if the test case defined parameters that we aren't doing anything with
        with(httpRequestTestCase) {
            if (authScheme.isPresent) {
                logger.warning("Test case provided authScheme but this was ignored")
            }
            if (!httpRequestTestCase.vendorParams.isEmpty) {
                logger.warning("Test case provided vendorParams but these were ignored")
            }
        }
    }

    private fun HttpMessageTestCase.action(): Action = when (this) {
        is HttpRequestTestCase -> Action.Request
        is HttpResponseTestCase -> Action.Response
        else -> throw CodegenException("Unknown test case type")
    }

    private fun expectFail(testCase: HttpMessageTestCase): Boolean = ExpectFail.find {
        it.id == testCase.id && it.action == testCase.action() && it.service == codegenContext.serviceShape.id.toString()
    } != null

    private fun RustWriter.renderHttpResponseTestCase(
        testCase: HttpResponseTestCase,
        expectedShape: StructureShape,
    ) {
        if (!protocolSupport.responseDeserialization || (
            !protocolSupport.errorDeserialization && expectedShape.hasTrait(
                    ErrorTrait::class.java,
                )
            )
        ) {
            rust("/* test case disabled for this protocol (not yet supported) */")
            return
        }
        writeInline("let expected_output =")
        instantiator.render(this, expectedShape, testCase.params)
        write(";")
        write("let http_response = #T::new()", RuntimeType.HttpResponseBuilder)
        testCase.headers.forEach { (key, value) ->
            writeWithNoFormatting(".header(${key.dq()}, ${value.dq()})")
        }
        rust(
            """
            .status(${testCase.code})
            .body(#T::from(${testCase.body.orNull()?.dq()?.replace("#", "##") ?: "vec![]"}))
            .unwrap();
            """,
            RuntimeType.sdkBody(runtimeConfig = codegenContext.runtimeConfig),
        )
        write(
            "let mut op_response = #T::new(http_response);",
            RuntimeType.operationModule(codegenContext.runtimeConfig).resolve("Response"),
        )
        rustTemplate(
            """
            use #{parse_http_response};
            let parser = #{op}::new();
            let parsed = parser.parse_unloaded(&mut op_response);
            let parsed = parsed.unwrap_or_else(|| {
                let (http_response, _) = op_response.into_parts();
                let http_response = http_response.map(|body|#{copy_from_slice}(body.bytes().unwrap()));
                <#{op} as #{parse_http_response}>::parse_loaded(&parser, &http_response)
            });
            """,
            "op" to operationSymbol,
            "copy_from_slice" to RuntimeType.Bytes.resolve("copy_from_slice"),
            "parse_http_response" to RuntimeType.parseHttpResponse(codegenContext.runtimeConfig),
        )
        if (expectedShape.hasTrait<ErrorTrait>()) {
            val errorSymbol =
                operationShape.errorSymbol(codegenContext.model, codegenContext.symbolProvider, codegenContext.target)
            val errorVariant = codegenContext.symbolProvider.toSymbol(expectedShape).name
            rust("""let parsed = parsed.expect_err("should be error response");""")
            rustBlock("if let #TKind::$errorVariant(actual_error) = parsed.kind", errorSymbol) {
                rustTemplate("#{AssertEq}(expected_output, actual_error);", *codegenScope)
            }
            rustBlock("else") {
                rust("panic!(\"wrong variant: Got: {:?}. Expected: {:?}\", parsed, expected_output);")
            }
        } else {
            rust("let parsed = parsed.unwrap();")
            outputShape.members().forEach { member ->
                val memberName = codegenContext.symbolProvider.toMemberName(member)
                if (member.isStreaming(codegenContext.model)) {
                    rustTemplate(
                        """
                        #{AssertEq}(
                            parsed.$memberName.collect().await.unwrap().into_bytes(),
                            expected_output.$memberName.collect().await.unwrap().into_bytes()
                        );
                        """,
                        *codegenScope,
                    )
                } else {
                    when (codegenContext.model.expectShape(member.target)) {
                        is DoubleShape, is FloatShape -> {
                            addUseImports(
                                RuntimeType.protocolTest(codegenContext.runtimeConfig, "FloatEquals").toSymbol(),
                            )
                            rust(
                                """
                                assert!(parsed.$memberName.float_equals(&expected_output.$memberName),
                                    "Unexpected value for `$memberName` {:?} vs. {:?}", expected_output.$memberName, parsed.$memberName);
                                """,
                            )
                        }

                        else ->
                            rustTemplate(
                                """#{AssertEq}(parsed.$memberName, expected_output.$memberName, "Unexpected value for `$memberName`");""",
                                *codegenScope,
                            )
                    }
                }
            }
        }
    }

    private fun checkBody(rustWriter: RustWriter, body: String, mediaType: String?) {
        rustWriter.write("""let body = http_request.body().bytes().expect("body should be strict");""")
        if (body == "") {
            rustWriter.rustTemplate(
                """
                // No body
                #{AssertEq}(std::str::from_utf8(body).unwrap(), "");
                """,
                *codegenScope,
            )
        } else {
            // When we generate a body instead of a stub, drop the trailing `;` and enable the assertion
            assertOk(rustWriter) {
                rustWriter.write(
                    "#T(&body, ${
                    rustWriter.escape(body).dq()
                    }, #T::from(${(mediaType ?: "unknown").dq()}))",
                    RuntimeType.protocolTest(codegenContext.runtimeConfig, "validate_body"),
                    RuntimeType.protocolTest(codegenContext.runtimeConfig, "MediaType"),
                )
            }
        }
    }

    private fun checkRequiredHeaders(rustWriter: RustWriter, actualExpression: String, requireHeaders: List<String>) {
        basicCheck(
            requireHeaders,
            rustWriter,
            "required_headers",
            actualExpression,
            "require_headers",
        )
    }

    private fun checkForbidHeaders(rustWriter: RustWriter, actualExpression: String, forbidHeaders: List<String>) {
        basicCheck(
            forbidHeaders,
            rustWriter,
            "forbidden_headers",
            actualExpression,
            "forbid_headers",
        )
    }

    private fun checkHeaders(rustWriter: RustWriter, actualExpression: String, headers: Map<String, String>) {
        if (headers.isEmpty()) {
            return
        }
        val variableName = "expected_headers"
        rustWriter.withBlock("let $variableName = [", "];") {
            writeWithNoFormatting(
                headers.entries.joinToString(",") {
                    "(${it.key.dq()}, ${it.value.dq()})"
                },
            )
        }
        assertOk(rustWriter) {
            write(
                "#T($actualExpression, $variableName)",
                RuntimeType.protocolTest(codegenContext.runtimeConfig, "validate_headers"),
            )
        }
    }

    private fun checkRequiredQueryParams(
        rustWriter: RustWriter,
        requiredParams: List<String>,
    ) = basicCheck(
        requiredParams,
        rustWriter,
        "required_params",
        "&http_request",
        "require_query_params",
    )

    private fun checkForbidQueryParams(
        rustWriter: RustWriter,
        forbidParams: List<String>,
    ) = basicCheck(
        forbidParams,
        rustWriter,
        "forbid_params",
        "&http_request",
        "forbid_query_params",
    )

    private fun checkQueryParams(
        rustWriter: RustWriter,
        queryParams: List<String>,
    ) = basicCheck(
        queryParams,
        rustWriter,
        "expected_query_params",
        "&http_request",
        "validate_query_string",
    )

    private fun basicCheck(
        params: List<String>,
        rustWriter: RustWriter,
        expectedVariableName: String,
        actualExpression: String,
        checkFunction: String,
    ) {
        if (params.isEmpty()) {
            return
        }
        rustWriter.withBlock("let $expectedVariableName = ", ";") {
            strSlice(this, params)
        }
        assertOk(rustWriter) {
            write(
                "#T($actualExpression, $expectedVariableName)",
                RuntimeType.protocolTest(codegenContext.runtimeConfig, checkFunction),
            )
        }
    }

    /**
     * wraps `inner` in a call to `aws_smithy_protocol_test::assert_ok`, a convenience wrapper
     * for pretty printing protocol test helper results
     */
    private fun assertOk(rustWriter: RustWriter, inner: Writable) {
        rustWriter.write("#T(", RuntimeType.protocolTest(codegenContext.runtimeConfig, "assert_ok"))
        inner(rustWriter)
        rustWriter.write(");")
    }

    private fun strSlice(writer: RustWriter, args: List<String>) {
        writer.withBlock("&[", "]") {
            write(args.joinToString(",") { it.dq() })
        }
    }

    companion object {
        sealed class Action {
            object Request : Action()
            object Response : Action()
        }

        data class FailingTest(val service: String, val id: String, val action: Action)

        // These tests fail due to shortcomings in our implementation.
        // These could be configured via runtime configuration, but since this won't be long-lasting,
        // it makes sense to do the simplest thing for now.
        // The test will _fail_ if these pass, so we will discover & remove if we fix them by accident
        private val JsonRpc10 = "aws.protocoltests.json10#JsonRpc10"
        private val AwsJson11 = "aws.protocoltests.json#JsonProtocol"
        private val RestJson = "aws.protocoltests.restjson#RestJson"
        private val RestXml = "aws.protocoltests.restxml#RestXml"
        private val AwsQuery = "aws.protocoltests.query#AwsQuery"
        private val Ec2Query = "aws.protocoltests.ec2#AwsEc2"
        private val ExpectFail = setOf<FailingTest>()
        private val RunOnly: Set<String>? = null

        // These tests are not even attempted to be generated, either because they will not compile
        // or because they are flaky
        private val DisableTests = setOf<String>()
    }
}
