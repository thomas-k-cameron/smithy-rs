/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package software.amazon.smithy.rustsdk

import software.amazon.smithy.rust.codegen.client.smithy.customizations.DocsRsMetadataDecorator
import software.amazon.smithy.rust.codegen.client.smithy.customizations.DocsRsMetadataSettings
import software.amazon.smithy.rust.codegen.client.smithy.customize.ClientCodegenDecorator
import software.amazon.smithy.rust.codegen.client.smithy.customize.CombinedClientCodegenDecorator
import software.amazon.smithy.rustsdk.customize.DisabledAuthDecorator
import software.amazon.smithy.rustsdk.customize.RemoveDefaultsDecorator
import software.amazon.smithy.rustsdk.customize.apigateway.ApiGatewayDecorator
import software.amazon.smithy.rustsdk.customize.applyDecorators
import software.amazon.smithy.rustsdk.customize.ec2.Ec2Decorator
import software.amazon.smithy.rustsdk.customize.glacier.GlacierDecorator
import software.amazon.smithy.rustsdk.customize.onlyApplyTo
import software.amazon.smithy.rustsdk.customize.route53.Route53Decorator
import software.amazon.smithy.rustsdk.customize.s3.S3Decorator
import software.amazon.smithy.rustsdk.customize.s3.S3ExtendedRequestIdDecorator
import software.amazon.smithy.rustsdk.customize.s3control.S3ControlDecorator
import software.amazon.smithy.rustsdk.customize.sso.SSODecorator
import software.amazon.smithy.rustsdk.customize.sts.STSDecorator
import software.amazon.smithy.rustsdk.customize.timestream.TimestreamDecorator
import software.amazon.smithy.rustsdk.endpoints.AwsEndpointsStdLib
import software.amazon.smithy.rustsdk.endpoints.OperationInputTestDecorator
import software.amazon.smithy.rustsdk.endpoints.RequireEndpointRules

val DECORATORS: List<ClientCodegenDecorator> = listOf(
    // General AWS Decorators
    listOf(
        CredentialsProviderDecorator(),
        RegionDecorator(),
        RequireEndpointRules(),
        UserAgentDecorator(),
        SigV4AuthDecorator(),
        HttpRequestChecksumDecorator(),
        HttpResponseChecksumDecorator(),
        RetryClassifierDecorator(),
        IntegrationTestDecorator(),
        AwsFluentClientDecorator(),
        CrateLicenseDecorator(),
        SdkConfigDecorator(),
        ServiceConfigDecorator(),
        AwsPresigningDecorator(),
        AwsCrateDocsDecorator(),
        AwsEndpointsStdLib(),
        *PromotedBuiltInsDecorators,
        GenericSmithySdkConfigSettings(),
        OperationInputTestDecorator(),
        AwsRequestIdDecorator(),
        DisabledAuthDecorator(),
        RecursionDetectionDecorator(),
        InvocationIdDecorator(),
        RetryInformationHeaderDecorator(),
        RemoveDefaultsDecorator(),
    ),

    // Service specific decorators
    ApiGatewayDecorator().onlyApplyTo("com.amazonaws.apigateway#BackplaneControlService"),
    Ec2Decorator().onlyApplyTo("com.amazonaws.ec2#AmazonEC2"),
    GlacierDecorator().onlyApplyTo("com.amazonaws.glacier#Glacier"),
    Route53Decorator().onlyApplyTo("com.amazonaws.route53#AWSDnsV20130401"),
    "com.amazonaws.s3#AmazonS3".applyDecorators(
        S3Decorator(),
        S3ExtendedRequestIdDecorator(),
    ),
    S3ControlDecorator().onlyApplyTo("com.amazonaws.s3control#AWSS3ControlServiceV20180820"),
    STSDecorator().onlyApplyTo("com.amazonaws.sts#AWSSecurityTokenServiceV20110615"),
    SSODecorator().onlyApplyTo("com.amazonaws.sso#SWBPortalService"),
    TimestreamDecorator().onlyApplyTo("com.amazonaws.timestreamwrite#Timestream_20181101"),
    TimestreamDecorator().onlyApplyTo("com.amazonaws.timestreamquery#Timestream_20181101"),

    // Only build docs-rs for linux to reduce load on docs.rs
    listOf(
        DocsRsMetadataDecorator(DocsRsMetadataSettings(targets = listOf("x86_64-unknown-linux-gnu"), allFeatures = true)),
    ),
).flatten()

class AwsCodegenDecorator : CombinedClientCodegenDecorator(DECORATORS) {
    override val name: String = "AwsSdkCodegenDecorator"
    override val order: Byte = -1
}
