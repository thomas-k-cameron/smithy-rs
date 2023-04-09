/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rust.codegen.client.smithy.protocols

import software.amazon.smithy.aws.traits.protocols.AwsJson1_0Trait
import software.amazon.smithy.aws.traits.protocols.AwsJson1_1Trait
import software.amazon.smithy.aws.traits.protocols.AwsQueryTrait
import software.amazon.smithy.aws.traits.protocols.Ec2QueryTrait
import software.amazon.smithy.aws.traits.protocols.RestJson1Trait
import software.amazon.smithy.aws.traits.protocols.RestXmlTrait
import software.amazon.smithy.rust.codegen.client.smithy.ClientCodegenContext
import software.amazon.smithy.rust.codegen.client.smithy.generators.protocol.ClientProtocolGenerator
import software.amazon.smithy.rust.codegen.core.smithy.CodegenContext
import software.amazon.smithy.rust.codegen.core.smithy.generators.protocol.ProtocolSupport
import software.amazon.smithy.rust.codegen.core.smithy.protocols.AwsJson
import software.amazon.smithy.rust.codegen.core.smithy.protocols.AwsJsonVersion
import software.amazon.smithy.rust.codegen.core.smithy.protocols.AwsQueryProtocol
import software.amazon.smithy.rust.codegen.core.smithy.protocols.Ec2QueryProtocol
import software.amazon.smithy.rust.codegen.core.smithy.protocols.Protocol
import software.amazon.smithy.rust.codegen.core.smithy.protocols.ProtocolGeneratorFactory
import software.amazon.smithy.rust.codegen.core.smithy.protocols.ProtocolLoader
import software.amazon.smithy.rust.codegen.core.smithy.protocols.ProtocolMap
import software.amazon.smithy.rust.codegen.core.smithy.protocols.RestJson
import software.amazon.smithy.rust.codegen.core.smithy.protocols.RestXml

class ClientProtocolLoader(supportedProtocols: ProtocolMap<ClientProtocolGenerator, ClientCodegenContext>) :
    ProtocolLoader<ClientProtocolGenerator, ClientCodegenContext>(supportedProtocols) {

    companion object {
        val DefaultProtocols = mapOf(
            AwsJson1_0Trait.ID to ClientAwsJsonFactory(AwsJsonVersion.Json10),
            AwsJson1_1Trait.ID to ClientAwsJsonFactory(AwsJsonVersion.Json11),
            AwsQueryTrait.ID to ClientAwsQueryFactory(),
            Ec2QueryTrait.ID to ClientEc2QueryFactory(),
            RestJson1Trait.ID to ClientRestJsonFactory(),
            RestXmlTrait.ID to ClientRestXmlFactory(),
        )
        val Default = ClientProtocolLoader(DefaultProtocols)
    }
}

private val CLIENT_PROTOCOL_SUPPORT = ProtocolSupport(
    /* Client protocol codegen enabled */
    requestSerialization = true,
    requestBodySerialization = true,
    responseDeserialization = true,
    errorDeserialization = true,
    /* Server protocol codegen disabled */
    requestDeserialization = false,
    requestBodyDeserialization = false,
    responseSerialization = false,
    errorSerialization = false,
)

private class ClientAwsJsonFactory(private val version: AwsJsonVersion) :
    ProtocolGeneratorFactory<HttpBoundProtocolGenerator, ClientCodegenContext> {
    override fun protocol(codegenContext: ClientCodegenContext): Protocol = AwsJson(codegenContext, version)

    override fun buildProtocolGenerator(codegenContext: ClientCodegenContext): HttpBoundProtocolGenerator =
        HttpBoundProtocolGenerator(codegenContext, protocol(codegenContext))

    override fun support(): ProtocolSupport = CLIENT_PROTOCOL_SUPPORT
}

private class ClientAwsQueryFactory : ProtocolGeneratorFactory<HttpBoundProtocolGenerator, ClientCodegenContext> {
    override fun protocol(codegenContext: ClientCodegenContext): Protocol = AwsQueryProtocol(codegenContext)

    override fun buildProtocolGenerator(codegenContext: ClientCodegenContext): HttpBoundProtocolGenerator =
        HttpBoundProtocolGenerator(codegenContext, protocol(codegenContext))

    override fun support(): ProtocolSupport = CLIENT_PROTOCOL_SUPPORT
}

private class ClientRestJsonFactory : ProtocolGeneratorFactory<HttpBoundProtocolGenerator, ClientCodegenContext> {
    override fun protocol(codegenContext: ClientCodegenContext): Protocol = RestJson(codegenContext)

    override fun buildProtocolGenerator(codegenContext: ClientCodegenContext): HttpBoundProtocolGenerator =
        HttpBoundProtocolGenerator(codegenContext, RestJson(codegenContext))

    override fun support(): ProtocolSupport = CLIENT_PROTOCOL_SUPPORT
}

private class ClientEc2QueryFactory : ProtocolGeneratorFactory<HttpBoundProtocolGenerator, ClientCodegenContext> {
    override fun protocol(codegenContext: ClientCodegenContext): Protocol = Ec2QueryProtocol(codegenContext)

    override fun buildProtocolGenerator(codegenContext: ClientCodegenContext): HttpBoundProtocolGenerator =
        HttpBoundProtocolGenerator(codegenContext, protocol(codegenContext))

    override fun support(): ProtocolSupport = CLIENT_PROTOCOL_SUPPORT
}

class ClientRestXmlFactory(
    private val generator: (CodegenContext) -> Protocol = { RestXml(it) },
) : ProtocolGeneratorFactory<HttpBoundProtocolGenerator, ClientCodegenContext> {
    override fun protocol(codegenContext: ClientCodegenContext): Protocol = generator(codegenContext)

    override fun buildProtocolGenerator(codegenContext: ClientCodegenContext): HttpBoundProtocolGenerator =
        HttpBoundProtocolGenerator(codegenContext, protocol(codegenContext))

    override fun support(): ProtocolSupport = CLIENT_PROTOCOL_SUPPORT
}
