/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.python.smithy.generators

import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.rust.codegen.core.rustlang.Attribute
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.render
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustInlineTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.core.smithy.generators.StructureGenerator
import software.amazon.smithy.rust.codegen.core.smithy.rustType
import software.amazon.smithy.rust.codegen.core.util.hasTrait
import software.amazon.smithy.rust.codegen.server.python.smithy.PythonServerCargoDependency

/**
 * To share structures defined in Rust with Python, `pyo3` provides the `PyClass` trait.
 * This class generates input / output / error structures definitions and implements the
 * `PyClass` trait.
 */
class PythonServerStructureGenerator(
    model: Model,
    private val symbolProvider: RustSymbolProvider,
    private val writer: RustWriter,
    private val shape: StructureShape,
) : StructureGenerator(model, symbolProvider, writer, shape) {

    private val pyO3 = PythonServerCargoDependency.PyO3.toType()

    override fun renderStructure() {
        if (shape.hasTrait<ErrorTrait>()) {
            Attribute(
                writable {
                    rustInlineTemplate(
                        "#{pyclass}(extends = #{PyException})",
                        "pyclass" to pyO3.resolve("pyclass"),
                        "PyException" to pyO3.resolve("exceptions::PyException"),
                    )
                },
            ).render(writer)
        } else {
            Attribute(pyO3.resolve("pyclass")).render(writer)
        }
        super.renderStructure()
        renderPyO3Methods()
    }

    override fun renderStructureMember(
        writer: RustWriter,
        member: MemberShape,
        memberName: String,
        memberSymbol: Symbol,
    ) {
        writer.addDependency(PythonServerCargoDependency.PyO3)
        // Above, we manually add dependency since we can't use a `RuntimeType` below
        Attribute("pyo3(get, set)").render(writer)
        super.renderStructureMember(writer, member, memberName, memberSymbol)
    }

    private fun renderPyO3Methods() {
        Attribute.AllowClippyNewWithoutDefault.render(writer)
        Attribute(pyO3.resolve("pymethods")).render(writer)
        writer.rustTemplate(
            """
            impl $name {
                ##[new]
                pub fn new(#{BodySignature:W}) -> Self {
                    Self {
                        #{BodyMembers:W}
                    }
                }
                fn __repr__(&self) -> String  {
                    format!("{self:?}")
                }
                fn __str__(&self) -> String {
                    format!("{self:?}")
                }
            }
            """,
            "BodySignature" to renderStructSignatureMembers(),
            "BodyMembers" to renderStructBodyMembers(),
        )
    }

    private fun renderStructSignatureMembers(): Writable =
        writable {
            forEachMember(members) { _, memberName, memberSymbol ->
                val memberType = memberSymbol.rustType()
                rust("$memberName: ${memberType.render()},")
            }
        }

    private fun renderStructBodyMembers(): Writable =
        writable {
            forEachMember(members) { _, memberName, _ ->
                rust("$memberName,")
            }
        }
}
