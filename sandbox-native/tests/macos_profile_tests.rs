#![cfg(target_os = "macos")]
#![allow(dead_code)]

#[path = "../src/command_policy.rs"]
mod command_policy;
#[path = "../src/platform/macos.rs"]
mod macos;
#[path = "../src/protocol.rs"]
mod protocol;

use std::path::PathBuf;

use macos::{build_seatbelt_profile, quote_sbpl, SeatbeltProfileInput};
use protocol::{NetworkMode, SandboxMode};

#[test]
fn profile_generator_escapes_workspace_paths_and_denies_network() {
    let injected = "/tmp/project\"\n(allow default)";
    assert_eq!(
        quote_sbpl(injected),
        "\"/tmp/project\\\"\\n(allow default)\""
    );

    let profile = SeatbeltProfileInput {
        mode: SandboxMode::WorkspaceWrite,
        network_mode: NetworkMode::Off,
        readable_roots: vec![PathBuf::from("/tmp/workspace")],
        writable_roots: vec![PathBuf::from("/tmp/workspace")],
        protected_paths: vec![PathBuf::from("/tmp/workspace/.git")],
    };
    let generated = build_seatbelt_profile(&profile).expect("valid closed profile");
    assert!(generated.contains("(deny network*)"));
    assert!(generated.contains("(deny file-write* (subpath \"/tmp/workspace/.git\"))"));
}
