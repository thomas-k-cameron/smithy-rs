/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

// This is the code used by CI to run the canary Lambda.
//
// If running this locally, you'll need to make a clone of awslabs/smithy-rs in
// the aws-sdk-rust project root.
//
// Also consider using the `AWS_PROFILE` and `AWS_REGION` environment variables
// when running this locally.
//
// CAUTION: This subcommand will `git reset --hard` in some cases. Don't ever run
// it against a smithy-rs repo that you're actively working in.

use std::path::PathBuf;
use std::str::FromStr;
use std::time::{Duration, SystemTime};
use std::{env, path::Path};

use anyhow::{bail, Context, Result};
use clap::Parser;
use cloudwatch::model::StandardUnit;
use s3::types::ByteStream;
use serde::Deserialize;
use smithy_rs_tool_common::git::{find_git_repository_root, Git, GitCLI};
use smithy_rs_tool_common::macros::here;
use smithy_rs_tool_common::release_tag::ReleaseTag;
use tracing::info;

use crate::build_bundle::BuildBundleArgs;

use aws_sdk_cloudwatch as cloudwatch;
use aws_sdk_lambda as lambda;
use aws_sdk_s3 as s3;

lazy_static::lazy_static! {
    // Occasionally, a breaking change introduced in smithy-rs will cause the canary to fail
    // for older versions of the SDK since the canary is in the smithy-rs repository and will
    // get fixed for that breaking change. When this happens, the older SDK versions can be
    // pinned to a commit hash in the smithy-rs repository to get old canary code that still
    // compiles against that version of the SDK.
    //
    // This is a map of SDK release tag to smithy-rs commit hash
    static ref PINNED_SMITHY_RS_VERSIONS: Vec<(ReleaseTag, &'static str)> = {
        let mut pinned = vec![
            // Versions <= 0.6.0 no longer compile against the canary after this commit in smithy-rs
            // due to the breaking change in https://github.com/awslabs/smithy-rs/pull/1085
            (ReleaseTag::from_str("v0.6.0").unwrap(), "d48c234796a16d518ca9e1dda5c7a1da4904318c"),
            // Versions <= release-2022-10-26 no longer compile against the canary after this commit in smithy-rs
            // due to the s3 canary update in https://github.com/awslabs/smithy-rs/pull/1974
            (ReleaseTag::from_str("release-2022-10-26").unwrap(), "3e24477ae7a0a2b3853962a064bc8333a016af54")
        ];
        pinned.sort();
        pinned
    };
}

#[derive(Debug, Parser, Eq, PartialEq)]
pub struct RunArgs {
    /// Rust version
    #[clap(long)]
    pub rust_version: Option<String>,

    /// Version of the SDK to compile the canary against
    #[clap(
        long,
        required_unless_present = "sdk-path",
        conflicts_with = "sdk-path"
    )]
    sdk_release_tag: Option<ReleaseTag>,

    /// Path to the SDK to compile against
    #[clap(
        long,
        required_unless_present = "sdk-release-tag",
        conflicts_with = "sdk-release-tag"
    )]
    sdk_path: Option<PathBuf>,

    /// Whether to target MUSL instead of GLIBC when compiling the Lambda
    #[clap(long)]
    musl: bool,

    /// File path to a CDK outputs JSON file. This can be used instead
    /// of all the --lambda... args.
    #[clap(long)]
    cdk_output: Option<PathBuf>,

    /// The name of the S3 bucket to upload the canary binary bundle to
    #[clap(long, required_unless_present = "cdk-output")]
    lambda_code_s3_bucket_name: Option<String>,

    /// The name of the S3 bucket for the canary Lambda to interact with
    #[clap(long, required_unless_present = "cdk-output")]
    lambda_test_s3_bucket_name: Option<String>,

    /// The ARN of the role that the Lambda will execute as
    #[clap(long, required_unless_present = "cdk-output")]
    lambda_execution_role_arn: Option<String>,
}

#[derive(Debug)]
struct Options {
    rust_version: Option<String>,
    sdk_release_tag: Option<ReleaseTag>,
    sdk_path: Option<PathBuf>,
    musl: bool,
    lambda_code_s3_bucket_name: String,
    lambda_test_s3_bucket_name: String,
    lambda_execution_role_arn: String,
}

impl Options {
    fn load_from(run_opt: RunArgs) -> Result<Options> {
        if let Some(cdk_output) = &run_opt.cdk_output {
            #[derive(Deserialize)]
            struct Inner {
                #[serde(rename = "canarycodebucketname")]
                lambda_code_s3_bucket_name: String,
                #[serde(rename = "canarytestbucketname")]
                lambda_test_s3_bucket_name: String,
                #[serde(rename = "lambdaexecutionrolearn")]
                lambda_execution_role_arn: String,
            }
            #[derive(Deserialize)]
            struct Outer {
                #[serde(rename = "aws-sdk-rust-canary-stack")]
                inner: Inner,
            }

            let value: Outer = serde_json::from_reader(
                std::fs::File::open(cdk_output).context("open cdk output")?,
            )
            .context("read cdk output")?;
            Ok(Options {
                rust_version: run_opt.rust_version,
                sdk_release_tag: run_opt.sdk_release_tag,
                sdk_path: run_opt.sdk_path,
                musl: run_opt.musl,
                lambda_code_s3_bucket_name: value.inner.lambda_code_s3_bucket_name,
                lambda_test_s3_bucket_name: value.inner.lambda_test_s3_bucket_name,
                lambda_execution_role_arn: value.inner.lambda_execution_role_arn,
            })
        } else {
            Ok(Options {
                rust_version: run_opt.rust_version,
                sdk_release_tag: run_opt.sdk_release_tag,
                sdk_path: run_opt.sdk_path,
                musl: run_opt.musl,
                lambda_code_s3_bucket_name: run_opt.lambda_code_s3_bucket_name.expect("required"),
                lambda_test_s3_bucket_name: run_opt.lambda_test_s3_bucket_name.expect("required"),
                lambda_execution_role_arn: run_opt.lambda_execution_role_arn.expect("required"),
            })
        }
    }
}

pub async fn run(opt: RunArgs) -> Result<()> {
    let options = Options::load_from(opt)?;
    let start_time = SystemTime::now();
    let config = aws_config::load_from_env().await;
    let result = run_canary(&options, &config).await;

    let mut metrics = vec![
        (
            "canary-success",
            if result.is_ok() { 1.0 } else { 0.0 },
            StandardUnit::Count,
        ),
        (
            "canary-failure",
            if result.is_ok() { 0.0 } else { 1.0 },
            StandardUnit::Count,
        ),
        (
            "canary-total-time",
            start_time.elapsed().expect("time in range").as_secs_f64(),
            StandardUnit::Seconds,
        ),
    ];
    if let Ok(invoke_time) = result {
        metrics.push((
            "canary-invoke-time",
            invoke_time.as_secs_f64(),
            StandardUnit::Seconds,
        ));
    }

    let cloudwatch_client = cloudwatch::Client::new(&config);
    let mut request_builder = cloudwatch_client
        .put_metric_data()
        .namespace("aws-sdk-rust-canary");
    for metric in metrics {
        request_builder = request_builder.metric_data(
            cloudwatch::model::MetricDatum::builder()
                .metric_name(metric.0)
                .value(metric.1)
                .timestamp(SystemTime::now().into())
                .unit(metric.2)
                .build(),
        );
    }

    info!("Emitting metrics...");
    request_builder
        .send()
        .await
        .context(here!("failed to emit metrics"))?;

    result.map(|_| ())
}

async fn run_canary(options: &Options, config: &aws_config::SdkConfig) -> Result<Duration> {
    let smithy_rs_root = find_git_repository_root("smithy-rs", ".").context(here!())?;
    let smithy_rs = GitCLI::new(&smithy_rs_root).context(here!())?;
    env::set_current_dir(smithy_rs_root.join("tools/ci-cdk/canary-lambda"))
        .context("failed to change working directory")?;

    if let Some(sdk_release_tag) = &options.sdk_release_tag {
        use_correct_revision(&smithy_rs, sdk_release_tag)
            .context(here!("failed to select correct revision of smithy-rs"))?;
    }

    info!("Building the canary...");
    let bundle_path = build_bundle(options).await?;
    let bundle_file_name = bundle_path.file_name().unwrap().to_str().unwrap();
    let bundle_name = bundle_path.file_stem().unwrap().to_str().unwrap();

    let s3_client = s3::Client::new(config);
    let lambda_client = lambda::Client::new(config);

    info!("Uploading Lambda code bundle to S3...");
    upload_bundle(
        s3_client,
        &options.lambda_code_s3_bucket_name,
        bundle_file_name,
        &bundle_path,
    )
    .await
    .context(here!())?;

    info!(
        "Creating the canary Lambda function named {}...",
        bundle_name
    );
    create_lambda_fn(
        lambda_client.clone(),
        bundle_name,
        bundle_file_name,
        &options.lambda_execution_role_arn,
        &options.lambda_code_s3_bucket_name,
        &options.lambda_test_s3_bucket_name,
    )
    .await
    .context(here!())?;

    info!("Invoking the canary Lambda...");
    let invoke_start_time = SystemTime::now();
    let invoke_result = invoke_lambda(lambda_client.clone(), bundle_name).await;
    let invoke_time = invoke_start_time.elapsed().expect("time in range");

    info!("Deleting the canary Lambda...");
    delete_lambda_fn(lambda_client, bundle_name)
        .await
        .context(here!())?;

    invoke_result.map(|_| invoke_time)
}

fn use_correct_revision(smithy_rs: &dyn Git, sdk_release_tag: &ReleaseTag) -> Result<()> {
    if let Some((pinned_release_tag, commit_hash)) = PINNED_SMITHY_RS_VERSIONS
        .iter()
        .find(|(pinned_release_tag, _)| pinned_release_tag >= sdk_release_tag)
    {
        info!("SDK `{pinned_release_tag}` requires smithy-rs@{commit_hash} to successfully compile the canary");
        // Reset to the revision rather than checkout since the very act of running the
        // canary-runner can make the working tree dirty by modifying the Cargo.lock file
        smithy_rs.hard_reset(commit_hash).context(here!())?;
    }
    Ok(())
}

/// Returns the path to the compiled bundle zip file
async fn build_bundle(options: &Options) -> Result<PathBuf> {
    let build_args = BuildBundleArgs {
        canary_path: None,
        rust_version: options.rust_version.clone(),
        sdk_release_tag: options.sdk_release_tag.clone(),
        sdk_path: options.sdk_path.clone(),
        musl: options.musl,
        manifest_only: false,
    };
    info!("Compiling the canary bundle for Lambda with {build_args:?}. This may take a few minutes...");
    Ok(crate::build_bundle::build_bundle(build_args)
        .await?
        .expect("manifest_only set to false, so there must be a bundle path"))
}

async fn upload_bundle(
    s3_client: s3::Client,
    s3_bucket: &str,
    file_name: &str,
    bundle_path: &Path,
) -> Result<()> {
    s3_client
        .put_object()
        .bucket(s3_bucket)
        .key(file_name)
        .body(
            ByteStream::from_path(bundle_path)
                .await
                .context(here!("failed to load bundle file"))?,
        )
        .send()
        .await
        .context(here!("failed to upload bundle to S3"))?;
    Ok(())
}

async fn create_lambda_fn(
    lambda_client: lambda::Client,
    bundle_name: &str,
    bundle_file_name: &str,
    execution_role: &str,
    code_s3_bucket: &str,
    test_s3_bucket: &str,
) -> Result<()> {
    use lambda::model::*;

    lambda_client
        .create_function()
        .function_name(bundle_name)
        .runtime(Runtime::Providedal2)
        .role(execution_role)
        .handler("aws-sdk-rust-lambda-canary")
        .code(
            FunctionCode::builder()
                .s3_bucket(code_s3_bucket)
                .s3_key(bundle_file_name)
                .build(),
        )
        .publish(true)
        .environment(
            Environment::builder()
                .variables("RUST_BACKTRACE", "1")
                .variables("RUST_LOG", "info")
                .variables("CANARY_S3_BUCKET_NAME", test_s3_bucket)
                .variables(
                    "CANARY_EXPECTED_TRANSCRIBE_RESULT",
                    "Good day to you transcribe. This is Polly talking to you from the Rust ST K.",
                )
                .build(),
        )
        .timeout(60)
        .send()
        .await
        .context(here!("failed to create canary Lambda function"))?;

    let mut attempts = 0;
    let mut state = State::Pending;
    while !matches!(state, State::Active) && attempts < 20 {
        info!("Waiting 1 second for Lambda to become active...");
        tokio::time::sleep(Duration::from_secs(1)).await;
        let configuration = lambda_client
            .get_function_configuration()
            .function_name(bundle_name)
            .send()
            .await
            .context(here!("failed to get Lambda function status"))?;
        state = configuration.state.unwrap();
        attempts += 1;
    }
    if !matches!(state, State::Active) {
        bail!("Timed out waiting for canary Lambda to become active");
    }
    Ok(())
}

async fn invoke_lambda(lambda_client: lambda::Client, bundle_name: &str) -> Result<()> {
    use lambda::model::*;
    use lambda::types::Blob;

    let response = lambda_client
        .invoke()
        .function_name(bundle_name)
        .invocation_type(InvocationType::RequestResponse)
        .log_type(LogType::Tail)
        .payload(Blob::new(&b"{}"[..]))
        .send()
        .await
        .context(here!("failed to invoke the canary Lambda"))?;

    if let Some(log_result) = response.log_result() {
        info!(
            "Last 4 KB of canary logs:\n----\n{}\n----\n",
            std::str::from_utf8(&base64::decode(log_result)?)?
        );
    }
    if response.status_code() != 200 || response.function_error().is_some() {
        bail!(
            "Canary failed: {}",
            response
                .function_error
                .as_deref()
                .unwrap_or("<no error given>")
        );
    }
    Ok(())
}

async fn delete_lambda_fn(lambda_client: lambda::Client, bundle_name: &str) -> Result<()> {
    lambda_client
        .delete_function()
        .function_name(bundle_name)
        .send()
        .await
        .context(here!("failed to delete Lambda"))?;
    Ok(())
}
