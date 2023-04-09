/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk

import software.amazon.smithy.rust.codegen.core.rustlang.CargoDependency
import software.amazon.smithy.rust.codegen.core.smithy.RuntimeConfig
import software.amazon.smithy.rust.codegen.core.smithy.crateLocation

fun RuntimeConfig.awsRuntimeCrate(name: String, features: Set<String> = setOf()): CargoDependency =
    CargoDependency(name, awsRoot().crateLocation(null), features = features)

object AwsCargoDependency {
    fun awsCredentialTypes(runtimeConfig: RuntimeConfig) = runtimeConfig.awsRuntimeCrate("aws-credential-types")
    fun awsConfig(runtimeConfig: RuntimeConfig) = runtimeConfig.awsRuntimeCrate("aws-config")
    fun awsEndpoint(runtimeConfig: RuntimeConfig) = runtimeConfig.awsRuntimeCrate("aws-endpoint")
    fun awsHttp(runtimeConfig: RuntimeConfig) = runtimeConfig.awsRuntimeCrate("aws-http")
    fun awsSigAuth(runtimeConfig: RuntimeConfig) = runtimeConfig.awsRuntimeCrate("aws-sig-auth")
    fun awsSigAuthEventStream(runtimeConfig: RuntimeConfig) = runtimeConfig.awsRuntimeCrate("aws-sig-auth", setOf("sign-eventstream"))
    fun awsSigv4(runtimeConfig: RuntimeConfig) = runtimeConfig.awsRuntimeCrate("aws-sigv4")
    fun awsTypes(runtimeConfig: RuntimeConfig) = runtimeConfig.awsRuntimeCrate("aws-types")
}
