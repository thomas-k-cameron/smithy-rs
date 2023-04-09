/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.core.rustlang.RustMetadata
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Visibility
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.deprecatedShape
import software.amazon.smithy.rust.codegen.core.rustlang.documentShape
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.core.util.toSnakeCase

/**
 * Generates a unified error enum for [operation]. [ErrorGenerator] handles generating the individual variants,
 * but we must still combine those variants into an enum covering all possible errors for a given operation.
 */
open class ServerOperationErrorGenerator(
    private val model: Model,
    private val symbolProvider: RustSymbolProvider,
    private val operationSymbol: Symbol,
    private val errors: List<StructureShape>,
) {
    open fun render(writer: RustWriter) {
        val symbol = RuntimeType("crate::error::${operationSymbol.name}Error")
        if (errors.isNotEmpty()) {
            renderErrors(writer, symbol, operationSymbol)
        }
    }

    fun renderErrors(
        writer: RustWriter,
        errorSymbol: RuntimeType,
        operationSymbol: Symbol,
    ) {
        val meta = RustMetadata(
            derives = setOf(RuntimeType.Debug),
            visibility = Visibility.PUBLIC,
        )

        writer.rust("/// Error type for the `${operationSymbol.name}` operation.")
        writer.rust("/// Each variant represents an error that can occur for the `${operationSymbol.name}` operation.")
        meta.render(writer)
        writer.rustBlock("enum ${errorSymbol.name}") {
            errors.forEach { errorVariant ->
                documentShape(errorVariant, model)
                deprecatedShape(errorVariant)
                val errorVariantSymbol = symbolProvider.toSymbol(errorVariant)
                write("${errorVariantSymbol.name}(#T),", errorVariantSymbol)
            }
        }

        writer.rustBlock("impl #T for ${errorSymbol.name}", RuntimeType.Display) {
            rustBlock("fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result") {
                delegateToVariants(errors, errorSymbol) {
                    rust("_inner.fmt(f)")
                }
            }
        }

        writer.rustBlock("impl ${errorSymbol.name}") {
            errors.forEach { error ->
                val errorVariantSymbol = symbolProvider.toSymbol(error)
                val fnName = errorVariantSymbol.name.toSnakeCase()
                writer.rust("/// Returns `true` if the error kind is `${errorSymbol.name}::${errorVariantSymbol.name}`.")
                writer.rustBlock("pub fn is_$fnName(&self) -> bool") {
                    rust("matches!(&self, ${errorSymbol.name}::${errorVariantSymbol.name}(_))")
                }
            }
            writer.rust("/// Returns the error name string by matching the correct variant.")
            writer.rustBlock("pub fn name(&self) -> &'static str") {
                delegateToVariants(errors, errorSymbol) {
                    rust("_inner.name()")
                }
            }
        }

        writer.rustBlock("impl #T for ${errorSymbol.name}", RuntimeType.StdError) {
            rustBlock("fn source(&self) -> Option<&(dyn #T + 'static)>", RuntimeType.StdError) {
                delegateToVariants(errors, errorSymbol) {
                    rust("Some(_inner)")
                }
            }
        }

        for (error in errors) {
            val errorVariantSymbol = symbolProvider.toSymbol(error)
            writer.rustBlock("impl #T<#T> for #T", RuntimeType.From, errorVariantSymbol, errorSymbol) {
                rustBlock("fn from(variant: #T) -> #T", errorVariantSymbol, errorSymbol) {
                    rust("Self::${errorVariantSymbol.name}(variant)")
                }
            }
        }
    }

    /**
     * Generates code to delegate behavior to the variants, for example:
     *
     * ```rust
     *  match &self {
     *      GreetingWithErrorsError::InvalidGreeting(_inner) => inner.fmt(f),
     *      GreetingWithErrorsError::ComplexError(_inner) => inner.fmt(f),
     *      GreetingWithErrorsError::FooError(_inner) => inner.fmt(f),
     *      GreetingWithErrorsError::Unhandled(_inner) => _inner.fmt(f),
     *  }
     *  ```
     *
     * A [writable] is passed containing the content to be written for each variant.
     *
     *  The field will always be bound as `_inner`.
     */
    private fun RustWriter.delegateToVariants(
        errors: List<StructureShape>,
        symbol: RuntimeType,
        writable: Writable,
    ) {
        rustBlock("match &self") {
            errors.forEach {
                val errorSymbol = symbolProvider.toSymbol(it)
                rust("""${symbol.name}::${errorSymbol.name}(_inner) => """)
                writable(this)
                write(",")
            }
        }
    }
}
