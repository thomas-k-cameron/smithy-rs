/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.generators
import software.amazon.smithy.codegen.core.Symbol
import software.amazon.smithy.codegen.core.SymbolProvider
import software.amazon.smithy.model.Model
import software.amazon.smithy.model.shapes.MemberShape
import software.amazon.smithy.model.shapes.Shape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.traits.ErrorTrait
import software.amazon.smithy.model.traits.SensitiveTrait
import software.amazon.smithy.rust.codegen.core.rustlang.RustType
import software.amazon.smithy.rust.codegen.core.rustlang.RustWriter
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.asDeref
import software.amazon.smithy.rust.codegen.core.rustlang.asRef
import software.amazon.smithy.rust.codegen.core.rustlang.deprecatedShape
import software.amazon.smithy.rust.codegen.core.rustlang.documentShape
import software.amazon.smithy.rust.codegen.core.rustlang.isCopy
import software.amazon.smithy.rust.codegen.core.rustlang.isDeref
import software.amazon.smithy.rust.codegen.core.rustlang.render
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustBlock
import software.amazon.smithy.rust.codegen.core.smithy.CodegenTarget
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RustSymbolProvider
import software.amazon.smithy.rust.codegen.core.smithy.expectRustMetadata
import software.amazon.smithy.rust.codegen.core.smithy.generators.error.ErrorGenerator
import software.amazon.smithy.rust.codegen.core.smithy.renamedFrom
import software.amazon.smithy.rust.codegen.core.smithy.rustType
import software.amazon.smithy.rust.codegen.core.util.dq
import software.amazon.smithy.rust.codegen.core.util.getTrait
import software.amazon.smithy.rust.codegen.core.util.redactIfNecessary

fun RustWriter.implBlock(structureShape: Shape, symbolProvider: SymbolProvider, block: Writable) {
    rustBlock("impl ${symbolProvider.toSymbol(structureShape).name}") {
        block()
    }
}

open class StructureGenerator(
    val model: Model,
    private val symbolProvider: RustSymbolProvider,
    private val writer: RustWriter,
    private val shape: StructureShape,
) {
    private val errorTrait = shape.getTrait<ErrorTrait>()
    protected val members: List<MemberShape> = shape.allMembers.values.toList()
    protected val accessorMembers: List<MemberShape> = when (errorTrait) {
        null -> members
        // Let the ErrorGenerator render the error message accessor if this is an error struct
        else -> members.filter { "message" != symbolProvider.toMemberName(it) }
    }
    protected val name = symbolProvider.toSymbol(shape).name

    fun render(forWhom: CodegenTarget = CodegenTarget.CLIENT) {
        renderStructure()
        errorTrait?.also { errorTrait ->
            ErrorGenerator(model, symbolProvider, writer, shape, errorTrait).render(forWhom)
        }
    }

    /**
     * Search for lifetimes used by the members of the struct and generate a declaration.
     * e.g. `<'a, 'b>`
     */
    private fun lifetimeDeclaration(): String {
        val lifetimes = members
            .map { symbolProvider.toSymbol(it).rustType() }
            .mapNotNull {
                when (it) {
                    is RustType.Reference -> it.lifetime
                    else -> null
                }
            }.toSet().sorted()
        return if (lifetimes.isNotEmpty()) {
            "<${lifetimes.joinToString { "'$it" }}>"
        } else ""
    }

    /**
     * Render a custom debug implementation
     * When [SensitiveTrait] support is required, render a custom debug implementation to redact sensitive data
     */
    private fun renderDebugImpl() {
        writer.rustBlock("impl ${lifetimeDeclaration()} #T for $name ${lifetimeDeclaration()}", RuntimeType.Debug) {
            writer.rustBlock("fn fmt(&self, f: &mut #1T::Formatter<'_>) -> #1T::Result", RuntimeType.stdFmt) {
                rust("""let mut formatter = f.debug_struct(${name.dq()});""")
                members.forEach { member ->
                    val memberName = symbolProvider.toMemberName(member)
                    val fieldValue = member.redactIfNecessary(model, "self.$memberName")

                    rust(
                        "formatter.field(${memberName.dq()}, &$fieldValue);",
                    )
                }
                rust("formatter.finish()")
            }
        }
    }

    private fun renderStructureImpl() {
        if (accessorMembers.isEmpty()) {
            return
        }
        writer.rustBlock("impl $name") {
            // Render field accessor methods
            forEachMember(accessorMembers) { member, memberName, memberSymbol ->
                renderMemberDoc(member, memberSymbol)
                writer.deprecatedShape(member)
                val memberType = memberSymbol.rustType()
                val returnType = when {
                    memberType.isCopy() -> memberType
                    memberType is RustType.Option && memberType.member.isDeref() -> memberType.asDeref()
                    memberType.isDeref() -> memberType.asDeref().asRef()
                    else -> memberType.asRef()
                }
                rustBlock("pub fn $memberName(&self) -> ${returnType.render()}") {
                    when {
                        memberType.isCopy() -> rust("self.$memberName")
                        memberType is RustType.Option && memberType.member.isDeref() -> rust("self.$memberName.as_deref()")
                        memberType is RustType.Option -> rust("self.$memberName.as_ref()")
                        memberType.isDeref() -> rust("use std::ops::Deref; self.$memberName.deref()")
                        else -> rust("&self.$memberName")
                    }
                }
            }
        }
    }

    open fun renderStructureMember(writer: RustWriter, member: MemberShape, memberName: String, memberSymbol: Symbol) {
        writer.renderMemberDoc(member, memberSymbol)
        SensitiveWarning.addDoc(writer, shape)
        writer.deprecatedShape(member)
        memberSymbol.expectRustMetadata().render(writer)
        writer.write("$memberName: #T,", memberSymbol)
    }

    open fun renderStructure() {
        val symbol = symbolProvider.toSymbol(shape)
        val containerMeta = symbol.expectRustMetadata()
        writer.documentShape(shape, model)
        writer.deprecatedShape(shape)
        RenderSerdeAttribute.forStructureShape(writer, shape, model)
        SensitiveWarning.addDoc(writer, shape)
        containerMeta.render(writer)

        writer.rustBlock("struct $name ${lifetimeDeclaration()}") {
            writer.forEachMember(members) { member, memberName, memberSymbol ->
                SensitiveWarning.addDoc(writer, shape)
                renderStructureMember(writer, member, memberName, memberSymbol)
            }
        }

        renderStructureImpl()
        if (!containerMeta.hasDebugDerive()) {
            renderDebugImpl()
        }
    }

    protected fun RustWriter.forEachMember(
        toIterate: List<MemberShape>,
        block: RustWriter.(MemberShape, String, Symbol) -> Unit,
    ) {
        toIterate.forEach { member ->
            val memberName = symbolProvider.toMemberName(member)
            val memberSymbol = symbolProvider.toSymbol(member)
            block(member, memberName, memberSymbol)
        }
    }

    private fun RustWriter.renderMemberDoc(member: MemberShape, memberSymbol: Symbol) {
        documentShape(
            member,
            model,
            note = memberSymbol.renamedFrom()
                ?.let { oldName -> "This member has been renamed from `$oldName`." },
        )
    }
}
