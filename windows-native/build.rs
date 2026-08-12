use std::{env, path::PathBuf, process::Command};

fn main() {
    println!("cargo:rerun-if-changed=app.rc");
    println!("cargo:rerun-if-changed=assets/app-icon.ico");

    if env::var_os("CARGO_CFG_WINDOWS").is_none() {
        return;
    }

    let output = PathBuf::from(env::var_os("OUT_DIR").expect("OUT_DIR is set by Cargo"))
        .join("codex_quota_resources.o");
    let status = Command::new("windres")
        .args(["app.rc", "-O", "coff", "-o"])
        .arg(&output)
        .status()
        .expect("windres is required to embed the Windows application icon");
    assert!(status.success(), "windres failed to compile app.rc");

    println!(
        "cargo:rustc-link-arg-bin=codex_quota_windows={}",
        output.display()
    );
}
