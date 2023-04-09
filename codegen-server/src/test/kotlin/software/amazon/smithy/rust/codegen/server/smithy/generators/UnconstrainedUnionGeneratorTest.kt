/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.server.smithy.generators

import org.junit.jupiter.api.Test
import software.amazon.smithy.model.shapes.StructureShape
import software.amazon.smithy.model.shapes.UnionShape
import software.amazon.smithy.rust.codegen.core.smithy.ModelsModule
import software.amazon.smithy.rust.codegen.core.smithy.UnconstrainedModule
import software.amazon.smithy.rust.codegen.core.smithy.generators.UnionGenerator
import software.amazon.smithy.rust.codegen.core.testutil.TestWorkspace
import software.amazon.smithy.rust.codegen.core.testutil.asSmithyModel
import software.amazon.smithy.rust.codegen.core.testutil.compileAndTest
import software.amazon.smithy.rust.codegen.core.testutil.unitTest
import software.amazon.smithy.rust.codegen.core.util.lookup
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverRenderWithModelBuilder
import software.amazon.smithy.rust.codegen.server.smithy.testutil.serverTestCodegenContext

class UnconstrainedUnionGeneratorTest {
    @Test
    fun `it should generate unconstrained unions`() {
        val model =
            """
            namespace test

            union Union {
                structure: Structure
            }

            structure Structure {
                @required
                requiredMember: String
            }
            """.asSmithyModel()
        val codegenContext = serverTestCodegenContext(model)
        val symbolProvider = codegenContext.symbolProvider

        val unionShape = model.lookup<UnionShape>("test#Union")

        val project = TestWorkspace.testProject(symbolProvider)

        project.withModule(ModelsModule) {
            model.lookup<StructureShape>("test#Structure").serverRenderWithModelBuilder(model, symbolProvider, this)
        }

        project.withModule(ModelsModule) {
            UnionGenerator(model, symbolProvider, this, unionShape, renderUnknownVariant = false).render()
        }
        project.withModule(UnconstrainedModule) unconstrainedModuleWriter@{
            project.withModule(ModelsModule) modelsModuleWriter@{
                UnconstrainedUnionGenerator(codegenContext, this@unconstrainedModuleWriter, this@modelsModuleWriter, unionShape).render()

                this@unconstrainedModuleWriter.unitTest(
                    name = "unconstrained_union_fail_to_constrain",
                    test = """
                        let builder = crate::model::Structure::builder();
                        let union_unconstrained = union_unconstrained::UnionUnconstrained::Structure(builder);

                        let expected_err = crate::model::union::ConstraintViolation::Structure(
                            crate::model::structure::ConstraintViolation::MissingRequiredMember,
                        );

                        assert_eq!(
                            expected_err,
                            crate::model::Union::try_from(union_unconstrained).unwrap_err()
                        );
                    """,
                )

                this@unconstrainedModuleWriter.unitTest(
                    name = "unconstrained_union_succeed_to_constrain",
                    test = """
                        let builder = crate::model::Structure::builder().required_member(String::from("david"));
                        let union_unconstrained = union_unconstrained::UnionUnconstrained::Structure(builder);

                        let expected: crate::model::Union = crate::model::Union::Structure(crate::model::Structure {
                            required_member: String::from("david"),
                        });
                        let actual: crate::model::Union = crate::model::Union::try_from(union_unconstrained).unwrap();

                        assert_eq!(expected, actual);
                    """,
                )

                this@unconstrainedModuleWriter.unitTest(
                    name = "unconstrained_union_converts_into_constrained",
                    test = """
                        let builder = crate::model::Structure::builder();
                        let union_unconstrained = union_unconstrained::UnionUnconstrained::Structure(builder);

                        let _union: crate::constrained::MaybeConstrained<crate::model::Union> =
                            union_unconstrained.into();
                    """,
                )
            }
        }
        project.compileAndTest()
    }
}
