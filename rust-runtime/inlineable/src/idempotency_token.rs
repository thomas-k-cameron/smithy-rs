/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

use aws_smithy_types::config_bag::{Storable, StoreReplace};
use std::sync::Mutex;

pub(crate) fn uuid_v4(input: u128) -> String {
    let mut out = String::with_capacity(36);
    // u4-aligned index into [input]
    let mut rnd_idx: u8 = 0;
    const HEX_CHARS: &[u8; 16] = b"0123456789abcdef";

    for str_idx in 0..36 {
        if str_idx == 8 || str_idx == 13 || str_idx == 18 || str_idx == 23 {
            out.push('-');
        // UUID version character
        } else if str_idx == 14 {
            out.push('4');
        } else {
            let mut dat: u8 = ((input >> (rnd_idx * 4)) & 0x0F) as u8;
            // UUID variant bits
            if str_idx == 19 {
                dat |= 0b00001000;
            }
            rnd_idx += 1;
            out.push(HEX_CHARS[dat as usize] as char);
        }
    }
    out
}

/// IdempotencyTokenProvider generates idempotency tokens for idempotency API requests
///
/// Generally, customers will not need to interact with this at all. A sensible default will be
/// provided automatically during config construction. However, if you need deterministic behavior
/// for testing, two options are available:
/// 1. Utilize the From<&'static str>` implementation to hard code an idempotency token
/// 2. Seed the token provider with [`IdempotencyTokenProvider::with_seed`](IdempotencyTokenProvider::with_seed)
#[derive(Debug)]
pub struct IdempotencyTokenProvider {
    inner: Inner,
}

#[derive(Debug)]
enum Inner {
    Static(&'static str),
    Random(Mutex<fastrand::Rng>),
}

pub fn default_provider() -> IdempotencyTokenProvider {
    IdempotencyTokenProvider::random()
}

impl From<&'static str> for IdempotencyTokenProvider {
    fn from(token: &'static str) -> Self {
        Self::fixed(token)
    }
}

impl Storable for IdempotencyTokenProvider {
    type Storer = StoreReplace<IdempotencyTokenProvider>;
}

impl IdempotencyTokenProvider {
    pub fn make_idempotency_token(&self) -> String {
        match &self.inner {
            Inner::Static(token) => token.to_string(),
            Inner::Random(rng) => {
                let input: u128 = rng.lock().unwrap().u128(..);
                uuid_v4(input)
            }
        }
    }

    pub fn with_seed(seed: u64) -> Self {
        Self {
            inner: Inner::Random(Mutex::new(fastrand::Rng::with_seed(seed))),
        }
    }

    pub fn random() -> Self {
        Self {
            inner: Inner::Random(Mutex::new(fastrand::Rng::new())),
        }
    }

    pub fn fixed(token: &'static str) -> Self {
        Self {
            inner: Inner::Static(token),
        }
    }
}

impl Clone for IdempotencyTokenProvider {
    fn clone(&self) -> Self {
        match &self.inner {
            Inner::Static(token) => IdempotencyTokenProvider::fixed(token),
            Inner::Random(_) => IdempotencyTokenProvider::random(),
        }
    }
}
