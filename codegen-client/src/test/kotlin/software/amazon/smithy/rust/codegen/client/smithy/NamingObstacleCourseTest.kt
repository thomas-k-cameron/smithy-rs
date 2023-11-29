/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy

import org.junit.jupiter.api.Test
import software.amazon.smithy.aws.traits.protocols.RestJson1Trait
import software.amazon.smithy.aws.traits.protocols.RestXmlTrait
import software.amazon.smithy.rust.codegen.client.testutil.clientIntegrationTest
import software.amazon.smithy.rust.codegen.core.testutil.NamingObstacleCourseTestModels.reusedInputOutputShapesModel
import software.amazon.smithy.rust.codegen.core.testutil.NamingObstacleCourseTestModels.rustPreludeEnumVariantsModel
import software.amazon.smithy.rust.codegen.core.testutil.NamingObstacleCourseTestModels.rustPreludeEnumsModel
import software.amazon.smithy.rust.codegen.core.testutil.NamingObstacleCourseTestModels.rustPreludeOperationsModel
import software.amazon.smithy.rust.codegen.core.testutil.NamingObstacleCourseTestModels.rustPreludeStructsModel

class NamingObstacleCourseTest {
    @Test
    fun `test Rust prelude operation names compile`() {
        clientIntegrationTest(rustPreludeOperationsModel()) { _, _ -> }
    }

    @Test
    fun `test Rust prelude structure names compile`() {
        clientIntegrationTest(rustPreludeStructsModel()) { _, _ -> }
    }

    @Test
    fun `test Rust prelude enum names compile`() {
        clientIntegrationTest(rustPreludeEnumsModel()) { _, _ -> }
    }

    @Test
    fun `test Rust prelude enum variant names compile`() {
        clientIntegrationTest(rustPreludeEnumVariantsModel()) { _, _ -> }
    }

    @Test
    fun `test reuse of input and output shapes json`() {
        clientIntegrationTest(reusedInputOutputShapesModel(RestJson1Trait.builder().build()))
    }

    @Test
    fun `test reuse of input and output shapes xml`() {
        clientIntegrationTest(reusedInputOutputShapesModel(RestXmlTrait.builder().build()))
    }
}
