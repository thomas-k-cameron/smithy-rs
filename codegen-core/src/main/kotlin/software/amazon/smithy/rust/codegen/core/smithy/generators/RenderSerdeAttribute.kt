/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.generators

import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.raw
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.core.util.isEventStream
import software.amazon.smithy.rust.codegen.core.util.isStreaming

// Part of RFC30
public object RenderSerdeAttribute {
    public fun addSerde(writer: RustWriter, shape: Shape? = null, model: Model? = null) {
        if (shape == null || model == null) return;
        if (shape.hasTrait<ErrorTrait>() && shape.members().none { it.isEventStream(model) }) {
            writeAttributes(writer)
        }
    }

    public fun skipIfStream(writer: RustWriter, member: MemberShape, model: Model) {
        if (member.isEventStream(model)) {
            return
        }
        if (member.isStreaming(model)) {
            Attribute("").SerdeSkip().render(writer)
        }
    }

    public fun importSerde(writer: RustWriter) {
        // we need this for skip serde to work
        Attribute.AllowUnusedImports.render(writer)
        Attribute("").SerdeSerializeOrDeserialize().render(writer)
        writer.raw("use serde;")
    }

    public fun writeAttributes(writer: RustWriter) {
        Attribute("").SerdeSerialize().render(writer)
        Attribute("").SerdeDeserialize().render(writer)
    }
}
