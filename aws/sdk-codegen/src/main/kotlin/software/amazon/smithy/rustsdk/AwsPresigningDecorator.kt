/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.HttpBinding
import software.amazon.smithy.model.knowledge.HttpBindingIndex
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.shapes.ToShapeId
import software.amazon.smithy.model.traits.HttpQueryTrait
import software.amazon.smithy.model.traits.HttpTrait
import software.amazon.smithy.model.transform.ModelTransformer
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.customize.ClientCodegenDecorator
import software.amazon.smithy.rust.codegen.client.smithy.generators.client.FluentClientCustomization
import software.amazon.smithy.rust.codegen.client.smithy.generators.client.FluentClientSection
import software.amazon.smithy.rust.codegen.client.smithy.generators.protocol.MakeOperationGenerator
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.docs
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlockTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.withBlock
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.customize.OperationCustomization
import software.amazon.smithy.rust.codegen.core.smithy.customize.OperationSection
import software.amazon.smithy.rust.codegen.core.smithy.generators.error.errorSymbol
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpBoundProtocolPayloadGenerator
import software.amazon.smithy.rust.codegen.core.util.cloneOperation
import software.amazon.smithy.rust.codegen.core.util.expectTrait
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rustsdk.AwsRuntimeType.defaultMiddleware
import software.amazon.smithy.rustsdk.traits.PresignableTrait
import kotlin.streams.toList

internal enum class PayloadSigningType {
    EMPTY,
    UNSIGNED_PAYLOAD,
}

private fun syntheticShapeId(shape: ToShapeId): ShapeId =
    shape.toShapeId().let { id -> ShapeId.fromParts(id.namespace + ".synthetic.aws.presigned", id.name) }

internal class PresignableOperation(
    val payloadSigningType: PayloadSigningType,
    val modelTransforms: List<PresignModelTransform> = emptyList(),
) {
    fun hasModelTransforms(): Boolean = modelTransforms.isNotEmpty()
}

private val SYNTHESIZE_SPEECH_OP = ShapeId.from("com.amazonaws.polly#SynthesizeSpeech")
internal val PRESIGNABLE_OPERATIONS by lazy {
    mapOf(
        // S3
        // TODO(https://github.com/awslabs/aws-sdk-rust/issues/488) Technically, all S3 operations support presigning
        ShapeId.from("com.amazonaws.s3#GetObject") to PresignableOperation(PayloadSigningType.UNSIGNED_PAYLOAD),
        ShapeId.from("com.amazonaws.s3#PutObject") to PresignableOperation(PayloadSigningType.UNSIGNED_PAYLOAD),
        ShapeId.from("com.amazonaws.s3#UploadPart") to PresignableOperation(PayloadSigningType.UNSIGNED_PAYLOAD),
        ShapeId.from("com.amazonaws.s3#DeleteObject") to PresignableOperation(PayloadSigningType.UNSIGNED_PAYLOAD),

        // Polly
        SYNTHESIZE_SPEECH_OP to PresignableOperation(
            PayloadSigningType.EMPTY,
            // Polly's SynthesizeSpeech operation has the HTTP method overridden to GET,
            // and the document members changed to query param members.
            modelTransforms = listOf(
                OverrideHttpMethodTransform(mapOf(SYNTHESIZE_SPEECH_OP to "GET")),
                MoveDocumentMembersToQueryParamsTransform(listOf(SYNTHESIZE_SPEECH_OP)),
            ),
        ),
    )
}

class AwsPresigningDecorator internal constructor(
    private val presignableOperations: Map<ShapeId, PresignableOperation> = PRESIGNABLE_OPERATIONS,
) : ClientCodegenDecorator {
    companion object {
        const val ORDER: Byte = 0
    }

    override val name: String = "AwsPresigning"
    override val order: Byte = ORDER

    override fun operationCustomizations(
        codegenContext: ClientCodegenContext,
        operation: OperationShape,
        baseCustomizations: List<OperationCustomization>,
    ): List<OperationCustomization> = baseCustomizations + listOf(AwsInputPresignedMethod(codegenContext, operation))

    /**
     * Adds presignable trait to known presignable operations and creates synthetic presignable shapes for codegen
     */
    override fun transformModel(service: ServiceShape, model: Model): Model {
        val modelWithSynthetics = addSyntheticOperations(model)
        val presignableTransforms = mutableListOf<PresignModelTransform>()
        val intermediate = ModelTransformer.create().mapShapes(modelWithSynthetics) { shape ->
            if (shape is OperationShape && presignableOperations.containsKey(shape.id)) {
                presignableTransforms.addAll(presignableOperations.getValue(shape.id).modelTransforms)
                shape.toBuilder().addTrait(PresignableTrait(syntheticShapeId(shape))).build()
            } else {
                shape
            }
        }
        // Apply operation-specific model transformations
        return presignableTransforms.fold(intermediate) { m, t -> t.transform(m) }
    }

    private fun addSyntheticOperations(model: Model): Model {
        val presignableOps = model.shapes()
            .filter { shape -> shape is OperationShape && presignableOperations.containsKey(shape.id) }
            .toList()
        return model.toBuilder().also { builder ->
            for (op in presignableOps) {
                builder.cloneOperation(model, op, ::syntheticShapeId)
            }
        }.build()
    }
}

class AwsInputPresignedMethod(
    private val codegenContext: CodegenContext,
    private val operationShape: OperationShape,
) : OperationCustomization() {
    private val runtimeConfig = codegenContext.runtimeConfig
    private val symbolProvider = codegenContext.symbolProvider

    private val codegenScope = arrayOf(
        "Error" to AwsRuntimeType.Presigning.resolve("config::Error"),
        "PresignedRequest" to AwsRuntimeType.Presigning.resolve("request::PresignedRequest"),
        "PresignedRequestService" to AwsRuntimeType.Presigning.resolve("service::PresignedRequestService"),
        "PresigningConfig" to AwsRuntimeType.Presigning.resolve("config::PresigningConfig"),
        "SdkError" to RuntimeType.sdkError(runtimeConfig),
        "aws_sigv4" to AwsRuntimeType.awsSigv4(runtimeConfig),
        "sig_auth" to AwsRuntimeType.awsSigAuth(runtimeConfig),
        "tower" to RuntimeType.Tower,
        "Middleware" to runtimeConfig.defaultMiddleware(),
    )

    override fun section(section: OperationSection): Writable =
        writable {
            if (section is OperationSection.InputImpl && section.operationShape.hasTrait<PresignableTrait>()) {
                writeInputPresignedMethod(section)
            }
        }

    private fun RustWriter.writeInputPresignedMethod(section: OperationSection.InputImpl) {
        val operationError = operationShape.errorSymbol(symbolProvider)
        val presignableOp = PRESIGNABLE_OPERATIONS.getValue(operationShape.id)

        val makeOperationOp = if (presignableOp.hasModelTransforms()) {
            codegenContext.model.expectShape(syntheticShapeId(operationShape.id), OperationShape::class.java)
        } else {
            section.operationShape
        }
        val makeOperationFn = "_make_presigned_operation"

        val protocol = section.protocol
        MakeOperationGenerator(
            codegenContext,
            protocol,
            HttpBoundProtocolPayloadGenerator(codegenContext, protocol),
            // Prefixed with underscore to avoid colliding with modeled functions
            functionName = makeOperationFn,
            public = false,
            includeDefaultPayloadHeaders = false,
        ).generateMakeOperation(this, makeOperationOp, section.customizations)

        documentPresignedMethod(hasConfigArg = true)
        rustBlockTemplate(
            """
            pub async fn presigned(
                self,
                config: &crate::config::Config,
                presigning_config: #{PresigningConfig}
            ) -> Result<#{PresignedRequest}, #{SdkError}<#{OpError}>>
            """,
            *codegenScope,
            "OpError" to operationError,
        ) {
            rustTemplate(
                """
                let (mut request, _) = self.$makeOperationFn(config)
                    .await
                    .map_err(#{SdkError}::construction_failure)?
                    .into_request_response();
                """,
                *codegenScope,
            )
            rustBlock("") {
                rust(
                    """
                    // Change signature type to query params and wire up presigning config
                    let mut props = request.properties_mut();
                    props.insert(presigning_config.start_time());
                    """,
                )
                withBlock("props.insert(", ");") {
                    rustTemplate(
                        "#{aws_sigv4}::http_request::SignableBody::" +
                            when (presignableOp.payloadSigningType) {
                                PayloadSigningType.EMPTY -> "Bytes(b\"\")"
                                PayloadSigningType.UNSIGNED_PAYLOAD -> "UnsignedPayload"
                            },
                        *codegenScope,
                    )
                }
                rustTemplate(
                    """
                    let mut config = props.get_mut::<#{sig_auth}::signer::OperationSigningConfig>()
                        .expect("signing config added by make_operation()");
                    config.signature_type = #{sig_auth}::signer::HttpSignatureType::HttpRequestQueryParams;
                    config.expires_in = Some(presigning_config.expires());
                    """,
                    *codegenScope,
                )
            }
            rustTemplate(
                """
                let middleware = #{Middleware}::default();
                let mut svc = #{tower}::builder::ServiceBuilder::new()
                    .layer(&middleware)
                    .service(#{PresignedRequestService}::new());

                use #{tower}::{Service, ServiceExt};
                Ok(svc.ready().await?.call(request).await?)
                """,
                *codegenScope,
            )
        }
    }
}

class AwsPresignedFluentBuilderMethod(
    runtimeConfig: RuntimeConfig,
) : FluentClientCustomization() {
    private val codegenScope = arrayOf(
        "Error" to AwsRuntimeType.Presigning.resolve("config::Error"),
        "PresignedRequest" to AwsRuntimeType.Presigning.resolve("request::PresignedRequest"),
        "PresigningConfig" to AwsRuntimeType.Presigning.resolve("config::PresigningConfig"),
        "SdkError" to RuntimeType.sdkError(runtimeConfig),
    )

    override fun section(section: FluentClientSection): Writable =
        writable {
            if (section is FluentClientSection.FluentBuilderImpl && section.operationShape.hasTrait(PresignableTrait::class.java)) {
                documentPresignedMethod(hasConfigArg = false)
                rustBlockTemplate(
                    """
                    pub async fn presigned(
                        self,
                        presigning_config: #{PresigningConfig},
                    ) -> Result<#{PresignedRequest}, #{SdkError}<#{OpError}>>
                    """,
                    *codegenScope,
                    "OpError" to section.operationErrorType,
                ) {
                    rustTemplate(
                        """
                        let input = self.inner.build().map_err(#{SdkError}::construction_failure)?;
                        input.presigned(&self.handle.conf, presigning_config).await
                        """,
                        *codegenScope,
                    )
                }
            }
        }
}

interface PresignModelTransform {
    fun transform(model: Model): Model
}

/**
 * Model transform that overrides HTTP request methods for the given map of operations.
 *
 * Note: this doesn't work for non-REST protocols. The protocol generators will need to be refactored
 * to respect HTTP traits or synthetic equivalents if this is needed for AwsQuery, Ec2Query, or AwsJson.
 */
class OverrideHttpMethodTransform(
    httpMethodOverrides: Map<ShapeId, String>,
) : PresignModelTransform {
    private val overrides = httpMethodOverrides.mapKeys { entry -> syntheticShapeId(entry.key) }

    override fun transform(model: Model): Model {
        return ModelTransformer.create().mapShapes(model) { shape ->
            if (shape is OperationShape && overrides.containsKey(shape.id)) {
                val newMethod = overrides.getValue(shape.id)
                check(shape.hasTrait(HttpTrait.ID)) {
                    "OverrideHttpMethodTransform can only be used with REST protocols"
                }
                val originalHttpTrait = shape.expectTrait<HttpTrait>()
                shape.toBuilder()
                    .removeTrait(HttpTrait.ID)
                    .addTrait(originalHttpTrait.toBuilder().method(newMethod).build())
                    .build()
            } else {
                shape
            }
        }
    }
}

/**
 * Model transform that moves document members into query parameters for the given list of operations.
 *
 * Note: this doesn't work for non-REST protocols. The protocol generators will need to be refactored
 * to respect HTTP traits or synthetic equivalents if this is needed for AwsQuery, Ec2Query, or AwsJson.
 */
class MoveDocumentMembersToQueryParamsTransform(
    private val presignableOperations: List<ShapeId>,
) : PresignModelTransform {
    override fun transform(model: Model): Model {
        val index = HttpBindingIndex(model)
        val operations = presignableOperations.map { id ->
            model.expectShape(syntheticShapeId(id), OperationShape::class.java).also { shape ->
                check(shape.hasTrait(HttpTrait.ID)) {
                    "MoveDocumentMembersToQueryParamsTransform can only be used with REST protocols"
                }
            }
        }

        // Find document members of the presignable operations
        val membersToUpdate = operations.map { operation ->
            val payloadBindings = index.getRequestBindings(operation, HttpBinding.Location.DOCUMENT)
            payloadBindings.map { binding -> binding.member }
        }.flatten()

        // Transform found shapes for presigning
        return ModelTransformer.create().mapShapes(model) { shape ->
            if (shape is MemberShape && membersToUpdate.contains(shape)) {
                shape.toBuilder().addTrait(HttpQueryTrait(shape.memberName)).build()
            } else {
                shape
            }
        }
    }
}

private fun RustWriter.documentPresignedMethod(hasConfigArg: Boolean) {
    val configBlurb = if (hasConfigArg) {
        "The credentials provider from the `config` will be used to generate the request's signature.\n"
    } else {
        ""
    }
    docs(
        """
        Creates a presigned request for this operation.

        ${configBlurb}The `presigning_config` provides additional presigning-specific config values, such as the
        amount of time the request should be valid for after creation.

        Presigned requests can be given to other users or applications to access a resource or perform
        an operation without having access to the AWS security credentials.
        """,
    )
}
