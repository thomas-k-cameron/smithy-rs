/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

//! Provides Sender/Receiver implementations for Event Stream codegen.

use std::error::Error as StdError;

mod receiver;
mod sender;

#[cfg(aws_sdk_unstable, feature = "deserialized")]
mod deserialized_stream;

pub type BoxError = Box<dyn StdError + Send + Sync + 'static>;

#[doc(inline)]
pub use sender::{EventStreamSender, MessageStreamAdapter, MessageStreamError};

#[doc(inline)]
pub use receiver::{RawMessage, Receiver, ReceiverError};

pub use deserialized_stream::*;