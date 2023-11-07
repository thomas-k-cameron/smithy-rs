/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.generators.client

import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.StringShape
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.core.rustlang.docsTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.util.inputShape
import software.amazon.smithy.rust.codegen.core.util.serviceNameOrDefault

object FluentClientDocs {
    fun clientConstructionDocs(codegenContext: ClientCodegenContext) = writable {
        val serviceName = codegenContext.serviceShape.serviceNameOrDefault("the service")
        val moduleUseName = codegenContext.moduleUseName()
        docsTemplate(
            """
            Client for calling $serviceName.

            #### Constructing a `Client`

            A `Client` requires a config in order to be constructed. With the default set of Cargo features,
            this config will only require an endpoint to produce a functioning client. However, some Smithy
            features will require additional configuration. For example, `@auth` requires some kind of identity
            or identity resolver to be configured. The config is used to customize various aspects of the client,
            such as:

              - [HTTP Connector](crate::config::Builder::http_connector)
              - [Retry](crate::config::Builder::retry_config)
              - [Timeouts](crate::config::Builder::timeout_config)
              - [... and more](crate::config::Builder)

            Below is a minimal example of how to create a client:

            ```rust,no_run
            let config = $moduleUseName::Config::builder()
                .endpoint_url("http://localhost:1234")
                .build();
            let client = $moduleUseName::Client::from_conf(config);
            ```

            _Note:_ Client construction is expensive due to connection thread pool initialization, and should be done
            once at application start-up. Cloning a client is cheap (it's just an [`Arc`](std::sync::Arc) under the hood),
            so creating it once at start-up and cloning it around the application as needed is recommended.
            """.trimIndent(),
        )
    }

    fun clientUsageDocs(codegenContext: ClientCodegenContext) = writable {
        val model = codegenContext.model
        val symbolProvider = codegenContext.symbolProvider
        if (model.operationShapes.isNotEmpty()) {
            // Find an operation with a simple string member shape
            val (operation, member) = codegenContext.serviceShape.operations
                .map { id ->
                    val operationShape = model.expectShape(id, OperationShape::class.java)
                    val member = operationShape.inputShape(model)
                        .members()
                        .firstOrNull { model.expectShape(it.target) is StringShape }
                    operationShape to member
                }
                .sortedBy { it.first.id }
                .firstOrNull { (_, member) -> member != null } ?: (null to null)
            if (operation != null && member != null) {
                val operationSymbol = symbolProvider.toSymbol(operation)
                val memberSymbol = symbolProvider.toSymbol(member)
                val operationFnName = FluentClientGenerator.clientOperationFnName(operation, symbolProvider)
                docsTemplate(
                    """
                    ## Using the `Client`

                    A client has a function for every operation that can be performed by the service.
                    For example, the [`${operationSymbol.name}`](${operationSymbol.namespace}) operation has
                    a [`Client::$operationFnName`], function which returns a builder for that operation.
                    The fluent builder ultimately has a `send()` function that returns an async future that
                    returns a result, as illustrated below:

                    ```rust,ignore
                    let result = client.$operationFnName()
                        .${memberSymbol.name}("example")
                        .send()
                        .await;
                    ```

                    The underlying HTTP requests that get made by this can be modified with the `customize_operation`
                    function on the fluent builder. See the [`customize`](crate::client::customize) module for more
                    information.
                    """.trimIndent(),
                    "operation" to operationSymbol,
                )
            }
        }
    }
}
