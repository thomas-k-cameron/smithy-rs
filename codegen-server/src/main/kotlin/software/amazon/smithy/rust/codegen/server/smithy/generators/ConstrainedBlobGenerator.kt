/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.shapes.BlobShape
import software.amazon.smithy.model.traits.LengthTrait
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Visibility
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.docs
import software.amazon.smithy.rust.codegen.core.rustlang.documentShape
import software.amazon.smithy.rust.codegen.core.rustlang.join
import software.amazon.smithy.rust.codegen.core.rustlang.render
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.expectRustMetadata
import software.amazon.smithy.rust.codegen.core.smithy.makeMaybeConstrained
import software.amazon.smithy.rust.codegen.core.smithy.module
import software.amazon.smithy.rust.codegen.core.smithy.rustType
import software.amazon.smithy.rust.codegen.core.util.orNull
import software.amazon.smithy.rust.codegen.server.smithy.PubCrateConstraintViolationSymbolProvider
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.traits.isReachableFromOperationInput
import software.amazon.smithy.rust.codegen.server.smithy.validationErrorMessage

class ConstrainedBlobGenerator(
    val codegenContext: ServerCodegenContext,
    val writer: RustWriter,
    val shape: BlobShape,
) {
    val model = codegenContext.model
    val constrainedShapeSymbolProvider = codegenContext.constrainedShapeSymbolProvider
    val publicConstrainedTypes = codegenContext.settings.codegenConfig.publicConstrainedTypes
    private val constraintViolationSymbolProvider =
        with(codegenContext.constraintViolationSymbolProvider) {
            if (publicConstrainedTypes) {
                this
            } else {
                PubCrateConstraintViolationSymbolProvider(this)
            }
        }
    private val constraintsInfo: List<TraitInfo> = listOf(LengthTrait::class.java)
        .mapNotNull { shape.getTrait(it).orNull() }
        .map { BlobLength(it).toTraitInfo() }

    fun render() {
        val symbol = constrainedShapeSymbolProvider.toSymbol(shape)
        val name = symbol.name
        val inner = RuntimeType.blob(codegenContext.runtimeConfig).toSymbol().rustType().render()
        val constraintViolation = constraintViolationSymbolProvider.toSymbol(shape)

        writer.documentShape(shape, model)
        writer.docs(rustDocsConstrainedTypeEpilogue(name))
        val metadata = symbol.expectRustMetadata()
        metadata.render(writer)
        writer.rust("struct $name(pub(crate) $inner);")
        writer.rustBlock("impl $name") {
            if (metadata.visibility == Visibility.PUBLIC) {
                writer.rust(
                    """
                    /// ${rustDocsInnerMethod(inner)}
                    pub fn inner(&self) -> &$inner {
                        &self.0
                    }
                    """,
                )
            }
            writer.rust(
                """
                /// ${rustDocsIntoInnerMethod(inner)}
                pub fn into_inner(self) -> $inner {
                    self.0
                }
                """,
            )
        }

        writer.renderTryFrom(inner, name, constraintViolation, constraintsInfo)

        writer.rustTemplate(
            """
            impl #{ConstrainedTrait} for $name  {
                type Unconstrained = $inner;
            }

            impl #{From}<$inner> for #{MaybeConstrained} {
                fn from(value: $inner) -> Self {
                    Self::Unconstrained(value)
                }
            }

            impl #{From}<$name> for $inner {
                fn from(value: $name) -> Self {
                    value.into_inner()
                }
            }
            """,
            "ConstrainedTrait" to RuntimeType.ConstrainedTrait,
            "ConstraintViolation" to constraintViolation,
            "MaybeConstrained" to symbol.makeMaybeConstrained(),
            "Display" to RuntimeType.Display,
            "From" to RuntimeType.From,
        )

        writer.withInlineModule(constraintViolation.module()) {
            renderConstraintViolationEnum(this, shape, constraintViolation)
        }
    }

    private fun renderConstraintViolationEnum(writer: RustWriter, shape: BlobShape, constraintViolation: Symbol) {
        writer.rustTemplate(
            """
            ##[derive(Debug, PartialEq)]
            pub enum ${constraintViolation.name} {
              #{Variants:W}
            }
            """,
            "Variants" to constraintsInfo.map { it.constraintViolationVariant }.join(",\n"),
        )

        if (shape.isReachableFromOperationInput()) {
            writer.rustTemplate(
                """
                impl ${constraintViolation.name} {
                    pub(crate) fn as_validation_exception_field(self, path: #{String}) -> crate::model::ValidationExceptionField {
                        match self {
                            #{ValidationExceptionFields:W}
                        }
                    }
                }
                """,
                "String" to RuntimeType.String,
                "ValidationExceptionFields" to constraintsInfo.map { it.asValidationExceptionField }.join("\n"),
            )
        }
    }
}

private data class BlobLength(val lengthTrait: LengthTrait) {
    fun toTraitInfo(): TraitInfo = TraitInfo(
        { rust("Self::check_length(&value)?;") },
        {
            docs("Error when a blob doesn't satisfy its `@length` requirements.")
            rust("Length(usize)")
        },
        {
            rust(
                """
                Self::Length(length) => crate::model::ValidationExceptionField {
                    message: format!("${lengthTrait.validationErrorMessage()}", length, &path),
                    path,
                },
                """,
            )
        },
        this::renderValidationFunction,
    )

    /**
     * Renders a `check_length` function to validate the blob matches the
     * required length indicated by the `@length` trait.
     */
    private fun renderValidationFunction(constraintViolation: Symbol, unconstrainedTypeName: String): Writable = {
        rust(
            """
            fn check_length(blob: &$unconstrainedTypeName) -> Result<(), $constraintViolation> {
                let length = blob.as_ref().len();

                if ${lengthTrait.rustCondition("length")} {
                    Ok(())
                } else {
                    Err($constraintViolation::Length(length))
                }
            }
            """,
        )
    }
}
