/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.generators.client

import software.amazon.smithy.model.shapes.OperationShape
import software.amazon.smithy.model.shapes.ServiceShape
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.customize.ClientCodegenDecorator
import software.amazon.smithy.rust.codegen.core.rustlang.Feature
import software.amazon.smithy.rust.codegen.core.rustlang.Writable
import software.amazon.smithy.rust.codegen.core.rustlang.rust
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate
import software.amazon.smithy.rust.codegen.core.smithy.customize.NamedCustomization
import software.amazon.smithy.rust.codegen.core.smithy.customize.Section
import software.amazon.smithy.rust.codegen.core.smithy.generators.LibRsCustomization
import software.amazon.smithy.rust.codegen.core.smithy.generators.LibRsSection

class FluentClientDecorator : ClientCodegenDecorator {
    override val name: String = "FluentClient"
    override val order: Byte = 0

    private fun applies(codegenContext: ClientCodegenContext): Boolean =
        codegenContext.settings.codegenConfig.includeFluentClient

    override fun extras(codegenContext: ClientCodegenContext, rustCrate: RustCrate) {
        if (!applies(codegenContext)) {
            return
        }

        FluentClientGenerator(
            codegenContext,
            customizations = listOf(GenericFluentClient(codegenContext)),
        ).render(rustCrate)
        rustCrate.mergeFeature(Feature("rustls", default = true, listOf("aws-smithy-client/rustls")))
        rustCrate.mergeFeature(Feature("native-tls", default = false, listOf("aws-smithy-client/native-tls")))
    }

    override fun libRsCustomizations(
        codegenContext: ClientCodegenContext,
        baseCustomizations: List<LibRsCustomization>,
    ): List<LibRsCustomization> {
        if (!applies(codegenContext)) {
            return baseCustomizations
        }

        return baseCustomizations + object : LibRsCustomization() {
            override fun section(section: LibRsSection) = when (section) {
                is LibRsSection.Body -> writable {
                    rust("pub use client::{Client, Builder};")
                }
                else -> emptySection
            }
        }
    }
}

sealed class FluentClientSection(name: String) : Section(name) {
    /** Write custom code into an operation fluent builder's impl block */
    data class FluentBuilderImpl(
        val operationShape: OperationShape,
        val operationErrorType: RuntimeType,
    ) : FluentClientSection("FluentBuilderImpl")

    /** Write custom code into the docs */
    data class FluentClientDocs(val serviceShape: ServiceShape) : FluentClientSection("FluentClientDocs")
}

abstract class FluentClientCustomization : NamedCustomization<FluentClientSection>()

class GenericFluentClient(codegenContext: CodegenContext) : FluentClientCustomization() {
    private val moduleUseName = codegenContext.moduleUseName()
    private val codegenScope = arrayOf("client" to RuntimeType.smithyClient(codegenContext.runtimeConfig))
    override fun section(section: FluentClientSection): Writable {
        return when (section) {
            is FluentClientSection.FluentClientDocs -> writable {
                val humanName = section.serviceShape.id.name
                rust(
                    """
                    /// An ergonomic service client for `$humanName`.
                    ///
                    /// This client allows ergonomic access to a `$humanName`-shaped service.
                    /// Each method corresponds to an endpoint defined in the service's Smithy model,
                    /// and the request and response shapes are auto-generated from that same model.
                    /// """,
                )
                rustTemplate(
                    """
                    /// ## Constructing a Client
                    ///
                    /// To construct a client, you need a few different things:
                    ///
                    /// - A [`Config`](crate::Config) that specifies additional configuration
                    ///   required by the service.
                    /// - A connector (`C`) that specifies how HTTP requests are translated
                    ///   into HTTP responses. This will typically be an HTTP client (like
                    ///   `hyper`), though you can also substitute in your own, like a mock
                    ///   mock connector for testing.
                    /// - A "middleware" (`M`) that modifies requests prior to them being
                    ///   sent to the request. Most commonly, middleware will decide what
                    ///   endpoint the requests should be sent to, as well as perform
                    ///   authentication and authorization of requests (such as SigV4).
                    ///   You can also have middleware that performs request/response
                    ///   tracing, throttling, or other middleware-like tasks.
                    /// - A retry policy (`R`) that dictates the behavior for requests that
                    ///   fail and should (potentially) be retried. The default type is
                    ///   generally what you want, as it implements a well-vetted retry
                    ///   policy implemented in [`RetryMode::Standard`](aws_smithy_types::retry::RetryMode::Standard).
                    ///
                    /// To construct a client, you will generally want to call
                    /// [`Client::with_config`], which takes a [`#{client}::Client`] (a
                    /// Smithy client that isn't specialized to a particular service),
                    /// and a [`Config`](crate::Config). Both of these are constructed using
                    /// the [builder pattern] where you first construct a `Builder` type,
                    /// then configure it with the necessary parameters, and then call
                    /// `build` to construct the finalized output type. The
                    /// [`#{client}::Client`] builder is re-exported in this crate as
                    /// [`Builder`] for convenience.
                    ///
                    /// In _most_ circumstances, you will want to use the following pattern
                    /// to construct a client:
                    ///
                    /// ```
                    /// use $moduleUseName::{Builder, Client, Config};
                    ///
                    /// let smithy_client = Builder::new()
                    ///       .dyn_https_connector(Default::default())
                    /// ##     /*
                    ///       .middleware(/* discussed below */)
                    /// ##     */
                    /// ##     .middleware_fn(|r| r)
                    ///       .build();
                    /// let config = Config::builder().endpoint_resolver("https://www.myurl.com").build();
                    /// let client = Client::with_config(smithy_client, config);
                    /// ```
                    ///
                    /// For the middleware, you'll want to use whatever matches the
                    /// routing, authentication and authorization required by the target
                    /// service. For example, for the standard AWS SDK which uses
                    /// [SigV4-signed requests], the middleware looks like this:
                    ///
                    // Ignored as otherwise we'd need to pull in all these dev-dependencies.
                    /// ```rust,ignore
                    /// use aws_endpoint::AwsEndpointStage;
                    /// use aws_http::auth::CredentialsStage;
                    /// use aws_http::recursion_detection::RecursionDetectionStage;
                    /// use aws_http::user_agent::UserAgentStage;
                    /// use aws_sig_auth::middleware::SigV4SigningStage;
                    /// use aws_sig_auth::signer::SigV4Signer;
                    /// use aws_smithy_client::retry::Config as RetryConfig;
                    /// use aws_smithy_http_tower::map_request::{AsyncMapRequestLayer, MapRequestLayer};
                    /// use std::fmt::Debug;
                    /// use tower::layer::util::{Identity, Stack};
                    /// use tower::ServiceBuilder;
                    ///
                    /// type AwsMiddlewareStack = Stack<
                    ///     MapRequestLayer<RecursionDetectionStage>,
                    ///     Stack<
                    ///         MapRequestLayer<SigV4SigningStage>,
                    ///         Stack<
                    ///             AsyncMapRequestLayer<CredentialsStage>,
                    ///             Stack<
                    ///                 MapRequestLayer<UserAgentStage>,
                    ///                 Stack<MapRequestLayer<AwsEndpointStage>, Identity>,
                    ///             >,
                    ///         >,
                    ///     >,
                    /// >;
                    ///
                    /// /// AWS Middleware Stack
                    /// ///
                    /// /// This implements the middleware stack for this service. It will:
                    /// /// 1. Load credentials asynchronously into the property bag
                    /// /// 2. Sign the request with SigV4
                    /// /// 3. Resolve an Endpoint for the request
                    /// /// 4. Add a user agent to the request
                    /// ##[derive(Debug, Default, Clone)]
                    /// ##[non_exhaustive]
                    /// pub struct AwsMiddleware;
                    ///
                    /// impl AwsMiddleware {
                    ///     /// Create a new `AwsMiddleware` stack
                    ///     ///
                    ///     /// Note: `AwsMiddleware` holds no state.
                    ///     pub fn new() -> Self {
                    ///         AwsMiddleware::default()
                    ///     }
                    /// }
                    ///
                    /// // define the middleware stack in a non-generic location to reduce code bloat.
                    /// fn base() -> ServiceBuilder<AwsMiddlewareStack> {
                    ///     let credential_provider = AsyncMapRequestLayer::for_mapper(CredentialsStage::new());
                    ///     let signer = MapRequestLayer::for_mapper(SigV4SigningStage::new(SigV4Signer::new()));
                    ///     let endpoint_resolver = MapRequestLayer::for_mapper(AwsEndpointStage);
                    ///     let user_agent = MapRequestLayer::for_mapper(UserAgentStage::new());
                    ///     let recursion_detection = MapRequestLayer::for_mapper(RecursionDetectionStage::new());
                    ///     // These layers can be considered as occurring in order, that is:
                    ///     // 1. Resolve an endpoint
                    ///     // 2. Add a user agent
                    ///     // 3. Acquire credentials
                    ///     // 4. Sign with credentials
                    ///     // (5. Dispatch over the wire)
                    ///     ServiceBuilder::new()
                    ///         .layer(endpoint_resolver)
                    ///         .layer(user_agent)
                    ///         .layer(credential_provider)
                    ///         .layer(signer)
                    ///         .layer(recursion_detection)
                    /// }
                    ///
                    /// impl<S> tower::Layer<S> for AwsMiddleware {
                    ///     type Service = <AwsMiddlewareStack as tower::Layer<S>>::Service;
                    ///
                    ///     fn layer(&self, inner: S) -> Self::Service {
                    ///         base().service(inner)
                    ///     }
                    /// }
                    /// ```
                    ///""",
                    *codegenScope,
                )
                rust(
                    """
                    /// ## Using a Client
                    ///
                    /// Once you have a client set up, you can access the service's endpoints
                    /// by calling the appropriate method on [`Client`]. Each such method
                    /// returns a request builder for that endpoint, with methods for setting
                    /// the various fields of the request. Once your request is complete, use
                    /// the `send` method to send the request. `send` returns a future, which
                    /// you then have to `.await` to get the service's response.
                    ///
                    /// [builder pattern]: https://rust-lang.github.io/api-guidelines/type-safety.html##c-builder
                    /// [SigV4-signed requests]: https://docs.aws.amazon.com/general/latest/gr/signature-version-4.html""",
                )
            }
            else -> emptySection
        }
    }
}
