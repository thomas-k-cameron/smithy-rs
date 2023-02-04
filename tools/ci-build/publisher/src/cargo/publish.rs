/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use crate::package::PackageHandle;
use anyhow::Result;
use smithy_rs_tool_common::shell::{capture_error, output_text, ShellOperation};
use std::path::PathBuf;
use std::process::Command;
use tracing::info;

pub struct Publish {
    program: &'static str,
    package_handle: PackageHandle,
    package_path: PathBuf,
}

impl Publish {
    pub fn new(package_handle: PackageHandle, package_path: impl Into<PathBuf>) -> Publish {
        Publish {
            program: "cargo",
            package_handle,
            package_path: package_path.into(),
        }
    }
}

impl ShellOperation for Publish {
    type Output = ();

    fn run(&self) -> Result<()> {
        let mut command = Command::new(self.program);
        command
            .current_dir(&self.package_path)
            .arg("publish")
            .arg("--jobs")
            .arg("1")
            .arg("--no-verify"); // The crates have already been built in previous CI steps
        let output = command.output()?;
        if !output.status.success() {
            let (stdout, stderr) = output_text(&output);
            let already_uploaded_msg = format!(
                "error: crate version `{}` is already uploaded",
                self.package_handle.version
            );
            if stdout.contains(&already_uploaded_msg) || stderr.contains(&already_uploaded_msg) {
                info!(
                    "{}-{} has already been published to crates.io.",
                    self.package_handle.name, self.package_handle.version
                );
            } else {
                return Err(capture_error("cargo publish", &output));
            }
        }
        Ok(())
    }
}

#[cfg(all(test, not(target_os = "windows")))]
mod tests {
    use super::*;
    use semver::Version;
    use std::env;

    #[tokio::test]
    async fn publish_succeeds() {
        Publish {
            program: "./fake_cargo/cargo_success",
            package_handle: PackageHandle::new(
                "aws-sdk-dynamodb",
                Version::parse("0.0.22-alpha").unwrap(),
            ),
            package_path: env::current_dir().unwrap(),
        }
        .spawn()
        .await
        .unwrap();
    }

    #[tokio::test]
    async fn publish_fails() {
        let result = Publish {
            program: "./fake_cargo/cargo_fails",
            package_handle: PackageHandle::new(
                "something",
                Version::parse("0.0.22-alpha").unwrap(),
            ),
            package_path: env::current_dir().unwrap(),
        }
        .spawn()
        .await;
        assert!(result.is_err(), "expected error, got {:?}", result);
        assert_eq!(
            "Failed to cargo publish:\n\
            Status: 1\n\
            Stdout: some stdout failure message\n\n\
            Stderr: some stderr failure message\n\n",
            format!("{}", result.err().unwrap())
        );
    }

    #[tokio::test]
    async fn publish_fails_already_uploaded() {
        Publish {
            program: "./fake_cargo/cargo_publish_already_published",
            package_handle: PackageHandle::new(
                "aws-sdk-dynamodb",
                Version::parse("0.0.22-alpha").unwrap(),
            ),
            package_path: env::current_dir().unwrap(),
        }
        .spawn()
        .await
        .unwrap();
    }
}
