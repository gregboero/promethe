#![cfg_attr(target_os = "windows", allow(dead_code))]

use crate::protocol::{ErrorCode, IpcRequest, IpcResponse};

#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "macos")]
mod macos;
#[cfg(target_os = "windows")]
mod windows;

#[cfg(target_os = "linux")]
use linux as current;
#[cfg(target_os = "macos")]
use macos as current;
#[cfg(target_os = "windows")]
use windows as current;

#[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
mod unsupported;
#[cfg(not(any(target_os = "linux", target_os = "macos", target_os = "windows")))]
use unsupported as current;

pub fn run_special_mode() -> Option<i32> {
    #[cfg(target_os = "windows")]
    {
        current::run_special_mode()
    }
    #[cfg(not(target_os = "windows"))]
    {
        None
    }
}

pub fn execute(request: IpcRequest) -> IpcResponse {
    let execution_id = request
        .execution
        .as_ref()
        .map(|execution| execution.execution_id.clone())
        .unwrap_or_default();
    let response = current::execute(request);
    if response.execution.is_none() {
        IpcResponse::execution_error(
            execution_id,
            response.error_code.unwrap_or(ErrorCode::InternalError),
            response
                .error_message
                .unwrap_or_else(|| "sandbox execution failed".into()),
        )
    } else {
        response
    }
}

pub fn cancel(request: IpcRequest) -> IpcResponse {
    current::cancel(request)
}

pub fn status() -> IpcResponse {
    current::status()
}

pub fn self_test() -> IpcResponse {
    current::self_test()
}
