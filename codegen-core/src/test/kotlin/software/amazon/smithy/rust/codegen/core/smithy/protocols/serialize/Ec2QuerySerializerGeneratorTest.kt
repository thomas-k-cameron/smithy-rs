/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.core.smithy.protocols.serialize

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.rust.codegen.core.rustlang.RustModule
import software.amazon.smithy.rust.codegen.core.smithy.generators.EnumGenerator
import software.amazon.smithy.rust.codegen.core.smithy.generators.UnionGenerator
import software.amazon.smithy.rust.codegen.core.smithy.transformers.OperationNormalizer
import software.amazon.smithy.rust.codegen.core.smithy.transformers.RecursiveShapeBoxer
import software.amazon.smithy.rust.codegen.core.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.core.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.core.testutil.renderWithModelBuilder
import software.amazon.smithy.rust.codegen.core.testutil.testCodegenContext
import software.amazon.smithy.rust.codegen.core.testutil.testSymbolProvider
import software.amazon.smithy.rust.codegen.core.testutil.unitTest
import software.amazon.smithy.rust.codegen.core.util.expectTrait
import software.amazon.smithy.rust.codegen.core.util.inputShape
import software.amazon.smithy.rust.codegen.core.util.lookup

class Ec2QuerySerializerGeneratorTest {
    private val baseModel = """
        namespace test

        union Choice {
            blob: Blob,
            boolean: Boolean,
            date: Timestamp,
            enum: FooEnum,
            int: Integer,
            @xmlFlattened
            list: SomeList,
            long: Long,
            map: MyMap,
            number: Double,
            s: String,
            top: Top,
            unit: Unit,
        }

        @enum([{name: "FOO", value: "FOO"}])
        string FooEnum

        map MyMap {
            key: String,
            value: Choice,
        }

        list SomeList {
            member: Choice
        }

        structure Top {
            choice: Choice,
            field: String,
            extra: Long,
            @xmlName("rec")
            recursive: TopList
        }

        list TopList {
            @xmlName("item")
            member: Top
        }

        structure OpInput {
            @xmlName("some_bool")
            boolean: Boolean,
            list: SomeList,
            map: MyMap,
            top: Top,
        }

        @http(uri: "/top", method: "POST")
        operation Op {
            input: OpInput,
        }
    """.asSmithyModel()

    @Test
    fun `generates valid serializers`() {
        val model = RecursiveShapeBoxer.transform(OperationNormalizer.transform(baseModel))
        val codegenContext = testCodegenContext(model)
        val symbolProvider = codegenContext.symbolProvider
        val parserGenerator = Ec2QuerySerializerGenerator(codegenContext)
        val operationGenerator = parserGenerator.operationInputSerializer(model.lookup("test#Op"))

        val project = TestWorkspace.testProject(testSymbolProvider(model))
        project.lib {
            unitTest(
                "ec2query_serializer",
                """
                use model::Top;

                let input = crate::input::OpInput::builder()
                    .top(
                        Top::builder()
                            .field("hello!")
                            .extra(45)
                            .recursive(Top::builder().extra(55).build())
                            .build()
                    )
                    .boolean(true)
                    .build()
                    .unwrap();
                let serialized = ${format(operationGenerator!!)}(&input).unwrap();
                let output = std::str::from_utf8(serialized.bytes().unwrap()).unwrap();
                assert_eq!(
                    output,
                    "\
                    Action=Op\
                    &Version=test\
                    &Some_bool=true\
                    &Top.Field=hello%21\
                    &Top.Extra=45\
                    &Top.Rec.1.Extra=55\
                    "
                );
                """,
            )
        }
        project.withModule(RustModule.public("model")) {
            model.lookup<StructureShape>("test#Top").renderWithModelBuilder(model, symbolProvider, this)
            UnionGenerator(model, symbolProvider, this, model.lookup("test#Choice")).render()
            val enum = model.lookup<StringShape>("test#FooEnum")
            EnumGenerator(model, symbolProvider, this, enum, enum.expectTrait()).render()
        }

        project.withModule(RustModule.public("input")) {
            model.lookup<OperationShape>("test#Op").inputShape(model).renderWithModelBuilder(model, symbolProvider, this)
        }
        project.compileAndTest()
    }
}
