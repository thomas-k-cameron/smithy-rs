/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize

import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ShapeId
import software.amazon.smithy.model.traits.XmlFlattenedTrait
import software.amazon.smithy.model.traits.XmlNameTrait
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.util.getTrait

class AwsQuerySerializerGenerator(codegenContext: CodegenContext) : QuerySerializerGenerator(codegenContext) {
    override val protocolName: String get() = "AWS Query"

    override fun MemberShape.queryKeyName(prioritizedFallback: String?): String =
        getTrait<XmlNameTrait>()?.value ?: memberName

    override fun MemberShape.isFlattened(): Boolean = getTrait<XmlFlattenedTrait>() != null

    override fun operationOutputSerializer(operationShape: OperationShape): RuntimeType? {
        TODO("Not yet implemented")
    }

    override fun serverErrorSerializer(shape: ShapeId): RuntimeType {
        TODO("Not yet implemented")
    }
}
