/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

mod plugin;

use std::{net::SocketAddr, sync::Arc};

use aws_smithy_http_server::{
    extension::OperationExtensionExt, instrumentation::InstrumentExt, plugin::PluginPipeline,
    request::request_id::ServerRequestIdProviderLayer, AddExtensionLayer,
};
use clap::Parser;

use plugin::PrintExt;

use pokemon_service::{
    do_nothing_but_log_request_ids, get_storage_with_local_approved, DEFAULT_ADDRESS, DEFAULT_PORT,
};
use pokemon_service_common::{
    capture_pokemon, check_health, get_pokemon_species, get_server_statistics, setup_tracing, State,
};
use pokemon_service_server_sdk::PokemonService;

#[derive(Parser, Debug)]
#[clap(author, version, about, long_about = None)]
struct Args {
    /// Hyper server bind address.
    #[clap(short, long, action, default_value = DEFAULT_ADDRESS)]
    address: String,
    /// Hyper server bind port.
    #[clap(short, long, action, default_value_t = DEFAULT_PORT)]
    port: u16,
}

#[tokio::main]
pub async fn main() {
    let args = Args::parse();
    setup_tracing();

    let plugins = PluginPipeline::new()
        // Apply the `PrintPlugin` defined in `plugin.rs`
        .print()
        // Apply the `OperationExtensionPlugin` defined in `aws_smithy_http_server::extension`. This allows other
        // plugins or tests to access a `aws_smithy_http_server::extension::OperationExtension` from
        // `Response::extensions`, or infer routing failure when it's missing.
        .insert_operation_extension()
        // Adds `tracing` spans and events to the request lifecycle.
        .instrument();
    let app = PokemonService::builder_with_plugins(plugins)
        // Build a registry containing implementations to all the operations in the service. These
        // are async functions or async closures that take as input the operation's input and
        // return the operation's output.
        .get_pokemon_species(get_pokemon_species)
        .get_storage(get_storage_with_local_approved)
        .get_server_statistics(get_server_statistics)
        .capture_pokemon(capture_pokemon)
        .do_nothing(do_nothing_but_log_request_ids)
        .check_health(check_health)
        .build()
        .expect("failed to build an instance of PokemonService");

    let app = app
        // Setup shared state and middlewares.
        .layer(&AddExtensionLayer::new(Arc::new(State::default())))
        // Add request IDs
        .layer(&ServerRequestIdProviderLayer::new());

    // Using `into_make_service_with_connect_info`, rather than `into_make_service`, to adjoin the `SocketAddr`
    // connection info.
    let make_app = app.into_make_service_with_connect_info::<SocketAddr>();

    // Bind the application to a socket.
    let bind: SocketAddr = format!("{}:{}", args.address, args.port)
        .parse()
        .expect("unable to parse the server bind address and port");
    let server = hyper::Server::bind(&bind).serve(make_app);

    // Run forever-ish...
    if let Err(err) = server.await {
        eprintln!("server error: {}", err);
    }
}
