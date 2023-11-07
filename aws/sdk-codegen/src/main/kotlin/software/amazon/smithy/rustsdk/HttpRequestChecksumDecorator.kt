/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk

import software.amazon.smithy.aws.traits.HttpChecksumTrait
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.customize.ClientCodegenDecorator
import software.amazon.smithy.rust.codegen.client.smithy.generators.OperationCustomization
import software.amazon.smithy.rust.codegen.client.smithy.generators.OperationSection
import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.rustlang.Visibility
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType.Companion.preludeScope
import software.amazon.smithy.rust.codegen.core.smithy.generators.operationBuildError
import software.amazon.smithy.rust.codegen.core.util.expectMember
import software.amazon.smithy.rust.codegen.core.util.getTrait
import software.amazon.smithy.rust.codegen.core.util.inputShape
import software.amazon.smithy.rust.codegen.core.util.orNull

private fun RuntimeConfig.awsInlineableHttpRequestChecksum() = RuntimeType.forInlineDependency(
    InlineAwsDependency.forRustFile(
        "http_request_checksum", visibility = Visibility.PUBCRATE,
        CargoDependency.Bytes,
        CargoDependency.Http,
        CargoDependency.HttpBody,
        CargoDependency.Tracing,
        CargoDependency.smithyChecksums(this),
        CargoDependency.smithyHttp(this),
        CargoDependency.smithyRuntimeApi(this),
        CargoDependency.smithyTypes(this),
    ),
)

class HttpRequestChecksumDecorator : ClientCodegenDecorator {
    override val name: String = "HttpRequestChecksum"
    override val order: Byte = 0

    override fun operationCustomizations(
        codegenContext: ClientCodegenContext,
        operation: OperationShape,
        baseCustomizations: List<OperationCustomization>,
    ): List<OperationCustomization> =
        baseCustomizations + HttpRequestChecksumCustomization(codegenContext, operation)
}

private fun HttpChecksumTrait.requestAlgorithmMember(
    codegenContext: ClientCodegenContext,
    operationShape: OperationShape,
): String? {
    val requestAlgorithmMember = this.requestAlgorithmMember.orNull() ?: return null
    val checksumAlgorithmMemberShape =
        operationShape.inputShape(codegenContext.model).expectMember(requestAlgorithmMember)

    return codegenContext.symbolProvider.toMemberName(checksumAlgorithmMemberShape)
}

private fun HttpChecksumTrait.checksumAlgorithmToStr(
    codegenContext: ClientCodegenContext,
    operationShape: OperationShape,
): Writable {
    val runtimeConfig = codegenContext.runtimeConfig
    val requestAlgorithmMember = this.requestAlgorithmMember(codegenContext, operationShape)
    val isRequestChecksumRequired = this.isRequestChecksumRequired

    return {
        if (requestAlgorithmMember != null) {
            if (isRequestChecksumRequired) {
                // Checksums are required, fall back to MD5
                rust("""let checksum_algorithm = checksum_algorithm.map(|algorithm| algorithm.as_str()).or(Some("md5"));""")
            } else {
                // Checksums aren't required, don't set a fallback
                rust("let checksum_algorithm = checksum_algorithm.map(|algorithm| algorithm.as_str());")
            }
        } else if (isRequestChecksumRequired) {
            // Checksums are required but a user can't set one, so we set MD5 for them
            rust("""let checksum_algorithm = Some("md5");""")
        }

        rustTemplate(
            """
            let checksum_algorithm = match checksum_algorithm {
                Some(algo) => Some(
                    algo.parse::<#{ChecksumAlgorithm}>()
                    .map_err(#{BuildError}::other)?
                ),
                None => None,
            };
            """,
            "BuildError" to runtimeConfig.operationBuildError(),
            "ChecksumAlgorithm" to RuntimeType.smithyChecksums(runtimeConfig).resolve("ChecksumAlgorithm"),
        )

        // If a request checksum is not required and there's no way to set one, do nothing
        // This happens when an operation only supports response checksums
    }
}

// This generator was implemented based on this spec:
// https://awslabs.github.io/smithy/1.0/spec/aws/aws-core.html#http-request-checksums
class HttpRequestChecksumCustomization(
    private val codegenContext: ClientCodegenContext,
    private val operationShape: OperationShape,
) : OperationCustomization() {
    private val runtimeConfig = codegenContext.runtimeConfig

    override fun section(section: OperationSection): Writable = writable {
        // Get the `HttpChecksumTrait`, returning early if this `OperationShape` doesn't have one
        val checksumTrait = operationShape.getTrait<HttpChecksumTrait>() ?: return@writable
        val requestAlgorithmMember = checksumTrait.requestAlgorithmMember(codegenContext, operationShape)
        val inputShape = codegenContext.model.expectShape(operationShape.inputShape)

        when (section) {
            is OperationSection.AdditionalInterceptors -> {
                if (requestAlgorithmMember != null) {
                    section.registerInterceptor(runtimeConfig, this) {
                        val runtimeApi = RuntimeType.smithyRuntimeApi(runtimeConfig)
                        rustTemplate(
                            """
                            #{RequestChecksumInterceptor}::new(|input: &#{Input}| {
                                let input: &#{OperationInput} = input.downcast_ref().expect("correct type");
                                let checksum_algorithm = input.$requestAlgorithmMember();
                                #{checksum_algorithm_to_str}
                                #{Result}::<_, #{BoxError}>::Ok(checksum_algorithm)
                            })
                            """,
                            *preludeScope,
                            "BoxError" to RuntimeType.boxError(runtimeConfig),
                            "Input" to runtimeApi.resolve("client::interceptors::context::Input"),
                            "OperationInput" to codegenContext.symbolProvider.toSymbol(inputShape),
                            "RequestChecksumInterceptor" to runtimeConfig.awsInlineableHttpRequestChecksum()
                                .resolve("RequestChecksumInterceptor"),
                            "checksum_algorithm_to_str" to checksumTrait.checksumAlgorithmToStr(
                                codegenContext,
                                operationShape,
                            ),
                        )
                    }
                }
            }
            else -> { }
        }
    }
}
