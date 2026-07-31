use crate::protocol::{
    ErrorCode, IpcOperation, IpcRequest, IpcResponse, NetworkMode, SandboxBackend, SandboxMode,
    Status, PROTOCOL_VERSION,
};

pub fn execute(_request: IpcRequest) -> IpcResponse {
    unavailable(IpcOperation::Execute)
}

pub fn cancel(_request: IpcRequest) -> IpcResponse {
    unavailable(IpcOperation::Cancel)
}

pub fn status() -> IpcResponse {
    IpcResponse {
        protocol_version: PROTOCOL_VERSION,
        operation: IpcOperation::Status,
        execution: None,
        status: Some(Status {
            protocol_version: PROTOCOL_VERSION,
            available: false,
            backend: SandboxBackend::Unavailable,
            mode: SandboxMode::ReadOnly,
            network_mode: NetworkMode::Off,
            degraded: false,
            setup_required: false,
            self_test_passed: false,
            message: Some("sandbox is unsupported on this platform".into()),
        }),
        error_code: None,
        error_message: None,
    }
}

pub fn self_test() -> IpcResponse {
    unavailable(IpcOperation::SelfTest)
}

fn unavailable(operation: IpcOperation) -> IpcResponse {
    IpcResponse {
        protocol_version: PROTOCOL_VERSION,
        operation,
        execution: None,
        status: None,
        error_code: Some(ErrorCode::BackendUnavailable),
        error_message: Some("sandbox is unsupported on this platform".into()),
    }
}
