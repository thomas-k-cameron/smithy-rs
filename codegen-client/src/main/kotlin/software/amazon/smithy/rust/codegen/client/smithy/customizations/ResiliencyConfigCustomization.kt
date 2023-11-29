/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.customizations

import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.ClientRustModule
import software.amazon.smithy.rust.codegen.client.smithy.configReexport
import software.amazon.smithy.rust.codegen.client.smithy.generators.config.ConfigCustomization
import software.amazon.smithy.rust.codegen.client.smithy.generators.config.ServiceConfig
import software.amazon.smithy.rust.codegen.core.rustlang.rustTemplate
import software.amazon.smithy.rust.codegen.core.rustlang.writable
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeType.Companion.preludeScope
import software.amazon.smithy.rust.codegen.core.smithy.RustCrate

class ResiliencyConfigCustomization(codegenContext: ClientCodegenContext) : ConfigCustomization() {
    private val runtimeConfig = codegenContext.runtimeConfig
    private val retryConfig = RuntimeType.smithyTypes(runtimeConfig).resolve("retry")
    private val sleepModule = RuntimeType.smithyAsync(runtimeConfig).resolve("rt::sleep")
    private val timeoutModule = RuntimeType.smithyTypes(runtimeConfig).resolve("timeout")
    private val retries = RuntimeType.smithyRuntime(runtimeConfig).resolve("client::retries")
    private val moduleUseName = codegenContext.moduleUseName()
    private val codegenScope = arrayOf(
        *preludeScope,
        "AsyncSleep" to configReexport(sleepModule.resolve("AsyncSleep")),
        "SharedAsyncSleep" to configReexport(sleepModule.resolve("SharedAsyncSleep")),
        "Sleep" to configReexport(sleepModule.resolve("Sleep")),
        "ClientRateLimiter" to retries.resolve("ClientRateLimiter"),
        "ClientRateLimiterPartition" to retries.resolve("ClientRateLimiterPartition"),
        "debug" to RuntimeType.Tracing.resolve("debug"),
        "IntoShared" to RuntimeType.smithyRuntimeApi(runtimeConfig).resolve("shared::IntoShared"),
        "RetryConfig" to retryConfig.resolve("RetryConfig"),
        "RetryMode" to RuntimeType.smithyTypes(runtimeConfig).resolve("retry::RetryMode"),
        "RetryPartition" to retries.resolve("RetryPartition"),
        "SharedAsyncSleep" to configReexport(sleepModule.resolve("SharedAsyncSleep")),
        "SharedRetryStrategy" to configReexport(RuntimeType.smithyRuntimeApiClient(runtimeConfig).resolve("client::retries::SharedRetryStrategy")),
        "SharedTimeSource" to configReexport(RuntimeType.smithyAsync(runtimeConfig).resolve("time::SharedTimeSource")),
        "StandardRetryStrategy" to configReexport(retries.resolve("strategy::StandardRetryStrategy")),
        "SystemTime" to RuntimeType.std.resolve("time::SystemTime"),
        "TimeoutConfig" to timeoutModule.resolve("TimeoutConfig"),
    )

    override fun section(section: ServiceConfig) =
        writable {
            when (section) {
                is ServiceConfig.ConfigImpl -> {
                    rustTemplate(
                        """
                        /// Return a reference to the retry configuration contained in this config, if any.
                        pub fn retry_config(&self) -> #{Option}<&#{RetryConfig}> {
                            self.config.load::<#{RetryConfig}>()
                        }

                        /// Return a cloned shared async sleep implementation from this config, if any.
                        pub fn sleep_impl(&self) -> #{Option}<#{SharedAsyncSleep}> {
                            self.runtime_components.sleep_impl()
                        }

                        /// Return a reference to the timeout configuration contained in this config, if any.
                        pub fn timeout_config(&self) -> #{Option}<&#{TimeoutConfig}> {
                            self.config.load::<#{TimeoutConfig}>()
                        }

                        /// Returns a reference to the retry partition contained in this config, if any.
                        ///
                        /// WARNING: This method is unstable and may be removed at any time. Do not rely on this
                        /// method for anything!
                        pub fn retry_partition(&self) -> #{Option}<&#{RetryPartition}> {
                            self.config.load::<#{RetryPartition}>()
                        }
                        """,
                        *codegenScope,
                    )
                }

                is ServiceConfig.BuilderImpl -> {
                    rustTemplate(
                        """
                        /// Set the retry_config for the builder
                        ///
                        /// ## Examples
                        /// ```no_run
                        /// use $moduleUseName::config::Config;
                        /// use $moduleUseName::config::retry::RetryConfig;
                        ///
                        /// let retry_config = RetryConfig::standard().with_max_attempts(5);
                        /// let config = Config::builder().retry_config(retry_config).build();
                        /// ```
                        pub fn retry_config(mut self, retry_config: #{RetryConfig}) -> Self {
                            self.set_retry_config(Some(retry_config));
                            self
                        }

                        /// Set the retry_config for the builder
                        ///
                        /// ## Examples
                        /// ```no_run
                        /// use $moduleUseName::config::{Builder, Config};
                        /// use $moduleUseName::config::retry::RetryConfig;
                        ///
                        /// fn disable_retries(builder: &mut Builder) {
                        ///     let retry_config = RetryConfig::standard().with_max_attempts(1);
                        ///     builder.set_retry_config(Some(retry_config));
                        /// }
                        ///
                        /// let mut builder = Config::builder();
                        /// disable_retries(&mut builder);
                        /// let config = builder.build();
                        /// ```
                        """,
                        *codegenScope,
                    )

                    rustTemplate(
                        """
                        pub fn set_retry_config(&mut self, retry_config: #{Option}<#{RetryConfig}>) -> &mut Self {
                            retry_config.map(|r| self.config.store_put(r));
                            self
                        }
                        """,
                        *codegenScope,
                    )

                    rustTemplate(
                        """

                        /// Set the sleep_impl for the builder
                        ///
                        /// ## Examples
                        ///
                        /// ```no_run
                        /// use $moduleUseName::config::{AsyncSleep, Config, SharedAsyncSleep, Sleep};
                        ///
                        /// ##[derive(Debug)]
                        /// pub struct ForeverSleep;
                        ///
                        /// impl AsyncSleep for ForeverSleep {
                        ///     fn sleep(&self, duration: std::time::Duration) -> Sleep {
                        ///         Sleep::new(std::future::pending())
                        ///     }
                        /// }
                        ///
                        /// let sleep_impl = SharedAsyncSleep::new(ForeverSleep);
                        /// let config = Config::builder().sleep_impl(sleep_impl).build();
                        /// ```
                        pub fn sleep_impl(mut self, sleep_impl: impl #{AsyncSleep} + 'static) -> Self {
                            self.set_sleep_impl(Some(#{IntoShared}::into_shared(sleep_impl)));
                            self
                        }

                        /// Set the sleep_impl for the builder
                        ///
                        /// ## Examples
                        ///
                        /// ```no_run
                        /// use $moduleUseName::config::{AsyncSleep, Builder, Config, SharedAsyncSleep, Sleep};
                        ///
                        /// ##[derive(Debug)]
                        /// pub struct ForeverSleep;
                        ///
                        /// impl AsyncSleep for ForeverSleep {
                        ///     fn sleep(&self, duration: std::time::Duration) -> Sleep {
                        ///         Sleep::new(std::future::pending())
                        ///     }
                        /// }
                        ///
                        /// fn set_never_ending_sleep_impl(builder: &mut Builder) {
                        ///     let sleep_impl = SharedAsyncSleep::new(ForeverSleep);
                        ///     builder.set_sleep_impl(Some(sleep_impl));
                        /// }
                        ///
                        /// let mut builder = Config::builder();
                        /// set_never_ending_sleep_impl(&mut builder);
                        /// let config = builder.build();
                        /// ```
                        """,
                        *codegenScope,
                    )

                    rustTemplate(
                        """
                        pub fn set_sleep_impl(&mut self, sleep_impl: #{Option}<#{SharedAsyncSleep}>) -> &mut Self {
                            self.runtime_components.set_sleep_impl(sleep_impl);
                            self
                        }
                        """,
                        *codegenScope,
                    )

                    rustTemplate(
                        """

                        /// Set the timeout_config for the builder
                        ///
                        /// ## Examples
                        ///
                        /// ```no_run
                        /// ## use std::time::Duration;
                        /// use $moduleUseName::config::Config;
                        /// use $moduleUseName::config::timeout::TimeoutConfig;
                        ///
                        /// let timeout_config = TimeoutConfig::builder()
                        ///     .operation_attempt_timeout(Duration::from_secs(1))
                        ///     .build();
                        /// let config = Config::builder().timeout_config(timeout_config).build();
                        /// ```
                        pub fn timeout_config(mut self, timeout_config: #{TimeoutConfig}) -> Self {
                            self.set_timeout_config(Some(timeout_config));
                            self
                        }

                        /// Set the timeout_config for the builder
                        ///
                        /// ## Examples
                        ///
                        /// ```no_run
                        /// ## use std::time::Duration;
                        /// use $moduleUseName::config::{Builder, Config};
                        /// use $moduleUseName::config::timeout::TimeoutConfig;
                        ///
                        /// fn set_request_timeout(builder: &mut Builder) {
                        ///     let timeout_config = TimeoutConfig::builder()
                        ///         .operation_attempt_timeout(Duration::from_secs(1))
                        ///         .build();
                        ///     builder.set_timeout_config(Some(timeout_config));
                        /// }
                        ///
                        /// let mut builder = Config::builder();
                        /// set_request_timeout(&mut builder);
                        /// let config = builder.build();
                        /// ```
                        """,
                        *codegenScope,
                    )

                    rustTemplate(
                        """
                        pub fn set_timeout_config(&mut self, timeout_config: #{Option}<#{TimeoutConfig}>) -> &mut Self {
                            timeout_config.map(|t| self.config.store_put(t));
                            self
                        }
                        """,
                        *codegenScope,
                    )

                    rustTemplate(
                        """
                        /// Set the partition for retry-related state. When clients share a retry partition, they will
                        /// also share things like token buckets and client rate limiters. By default, all clients
                        /// for the same service will share a partition.
                        pub fn retry_partition(mut self, retry_partition: #{RetryPartition}) -> Self {
                            self.set_retry_partition(Some(retry_partition));
                            self
                        }
                        """,
                        *codegenScope,
                    )

                    rustTemplate(
                        """
                        /// Set the partition for retry-related state. When clients share a retry partition, they will
                        /// also share things like token buckets and client rate limiters. By default, all clients
                        /// for the same service will share a partition.
                        pub fn set_retry_partition(&mut self, retry_partition: #{Option}<#{RetryPartition}>) -> &mut Self {
                            retry_partition.map(|r| self.config.store_put(r));
                            self
                        }
                        """,
                        *codegenScope,
                    )
                }

                else -> emptySection
            }
        }
}

class ResiliencyReExportCustomization(codegenContext: ClientCodegenContext) {
    private val runtimeConfig = codegenContext.runtimeConfig

    fun extras(rustCrate: RustCrate) {
        rustCrate.withModule(ClientRustModule.config) {
            rustTemplate(
                "pub use #{sleep}::{Sleep};",
                "sleep" to RuntimeType.smithyAsync(runtimeConfig).resolve("rt::sleep"),
            )
        }
        rustCrate.withModule(ClientRustModule.Config.retry) {
            rustTemplate(
                "pub use #{types_retry}::{RetryConfig, RetryConfigBuilder, RetryMode, ReconnectMode};",
                "types_retry" to RuntimeType.smithyTypes(runtimeConfig).resolve("retry"),
            )

            rustTemplate(
                "pub use #{types_retry}::RetryPartition;",
                "types_retry" to RuntimeType.smithyRuntime(runtimeConfig).resolve("client::retries"),
            )
        }
        rustCrate.withModule(ClientRustModule.Config.timeout) {
            rustTemplate(
                "pub use #{timeout}::{TimeoutConfig, TimeoutConfigBuilder};",
                "timeout" to RuntimeType.smithyTypes(runtimeConfig).resolve("timeout"),
            )
        }
    }
}
