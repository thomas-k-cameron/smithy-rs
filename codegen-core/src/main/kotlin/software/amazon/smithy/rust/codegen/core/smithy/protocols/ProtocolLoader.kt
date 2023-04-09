/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.protocols

import software.amazon.smithy.codegen.core.CodegenException
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.knowledge.ServiceIndex
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.traits.Trait
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext

open class ProtocolLoader<T, C : CodegenContext>(private val supportedProtocols: ProtocolMap<T, C>) {
    fun protocolFor(
        model: Model,
        serviceShape: ServiceShape,
    ): Pair<ShapeId, ProtocolGeneratorFactory<T, C>> {
        val protocols: MutableMap<ShapeId, Trait> = ServiceIndex.of(model).getProtocols(serviceShape)
        val matchingProtocols =
            protocols.keys.mapNotNull { protocolId -> supportedProtocols[protocolId]?.let { protocolId to it } }
        if (matchingProtocols.isEmpty()) {
            throw CodegenException("No matching protocol — service offers: ${protocols.keys}. We offer: ${supportedProtocols.keys}")
        }
        return matchingProtocols.first()
    }
}
