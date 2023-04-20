/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators.http

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.stripOuter
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.generators.http.HttpBindingCustomization
import software.amazon.smithy.rust.codegen.core.smithy.generators.http.HttpBindingGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.http.HttpBindingSection
import software.amazon.smithy.rust.codegen.core.smithy.generators.http.HttpMessageType
import software.amazon.smithy.rust.codegen.core.smithy.mapRustType
import software.amazon.smithy.rust.codegen.core.smithy.protocols.HttpBindingDescriptor
import software.amazon.smithy.rust.codegen.core.smithy.protocols.Protocol
import software.amazon.smithy.rust.codegen.server.smithy.ServerCodegenContext
import software.amazon.smithy.rust.codegen.server.smithy.targetCanReachConstrainedShape

class ServerRequestBindingGenerator(
    protocol: Protocol,
    codegenContext: ServerCodegenContext,
    operationShape: OperationShape,
    additionalHttpBindingCustomizations: List<HttpBindingCustomization> = listOf(),
) {
    private val httpBindingGenerator =
        HttpBindingGenerator(
            protocol,
            codegenContext,
            codegenContext.unconstrainedShapeSymbolProvider,
            operationShape,
            listOf(
                ServerRequestAfterDeserializingIntoAHashMapOfHttpPrefixHeadersWrapInUnconstrainedMapHttpBindingCustomization(
                    codegenContext,
                ),
            ) + additionalHttpBindingCustomizations,
        )

    fun generateDeserializeHeaderFn(binding: HttpBindingDescriptor): RuntimeType =
        httpBindingGenerator.generateDeserializeHeaderFn(binding)

    fun generateDeserializePayloadFn(
        binding: HttpBindingDescriptor,
        errorSymbol: Symbol,
        structuredHandler: RustWriter.(String) -> Unit,
    ): RuntimeType = httpBindingGenerator.generateDeserializePayloadFn(
        binding,
        errorSymbol,
        structuredHandler,
        HttpMessageType.REQUEST,
    )

    fun generateDeserializePrefixHeadersFn(
        binding: HttpBindingDescriptor,
    ): RuntimeType = httpBindingGenerator.generateDeserializePrefixHeaderFn(binding)
}

/**
 * A customization to, just after we've deserialized HTTP request headers bound to a map shape via `@httpPrefixHeaders`,
 * wrap the `std::collections::HashMap` in an unconstrained type wrapper newtype.
 */
class ServerRequestAfterDeserializingIntoAHashMapOfHttpPrefixHeadersWrapInUnconstrainedMapHttpBindingCustomization(val codegenContext: ServerCodegenContext) :
    HttpBindingCustomization() {
    override fun section(section: HttpBindingSection): Writable = when (section) {
        is HttpBindingSection.BeforeRenderingHeaderValue,
        is HttpBindingSection.BeforeIteratingOverMapShapeBoundWithHttpPrefixHeaders,
        -> emptySection
        is HttpBindingSection.AfterDeserializingIntoAHashMapOfHttpPrefixHeaders -> writable {
            if (section.memberShape.targetCanReachConstrainedShape(codegenContext.model, codegenContext.unconstrainedShapeSymbolProvider)) {
                rust(
                    "let out = out.map(#T);",
                    codegenContext.unconstrainedShapeSymbolProvider.toSymbol(section.memberShape).mapRustType {
                        it.stripOuter<RustType.Option>()
                    },
                )
            }
        }
        else -> emptySection
    }
}
