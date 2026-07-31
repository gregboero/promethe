use std::collections::{BTreeMap, BTreeSet};

use serde::{Deserialize, Serialize};

pub const PROTOCOL_VERSION: u32 = 1;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum IpcOperation {
    Execute,
    Cancel,
    Status,
    SelfTest,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SandboxMode {
    ReadOnly,
    WorkspaceWrite,
    FullAccess,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ApprovalPolicy {
    OnRequest,
    Untrusted,
    Never,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum NetworkMode {
    Off,
    Allowlist,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum SandboxBackend {
    LinuxBwrap,
    MacosSeatbelt,
    WindowsElevated,
    Docker,
    Wsl2,
    Unavailable,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ErrorCode {
    InvalidRequest,
    BackendUnavailable,
    SetupRequired,
    PolicyDenied,
    NetworkDenied,
    WorkspaceViolation,
    ResourceLimit,
    TimedOut,
    Cancelled,
    ProtocolError,
    InternalError,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ResourceLimits {
    pub timeout_millis: u64,
    pub max_output_bytes_per_stream: usize,
    pub memory_bytes: u64,
    pub cpu_limit: f64,
    pub process_limit: u32,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PermissionProfile {
    pub mode: SandboxMode,
    pub approval_policy: ApprovalPolicy,
    pub network_mode: NetworkMode,
    pub readable_roots: Vec<String>,
    pub writable_roots: Vec<String>,
    pub protected_paths: Vec<String>,
    pub allowed_domains: Vec<String>,
    pub limits: ResourceLimits,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecutionRequest {
    pub protocol_version: u32,
    pub execution_id: String,
    pub session_id: String,
    pub executable: String,
    pub arguments: Vec<String>,
    pub working_directory: String,
    pub environment: BTreeMap<String, String>,
    pub sensitive_environment_keys: BTreeSet<String>,
    pub profile: PermissionProfile,
    pub interactive: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ExecutionResult {
    pub protocol_version: u32,
    pub execution_id: String,
    pub exit_code: Option<i32>,
    pub stdout: String,
    pub stderr: String,
    pub timed_out: bool,
    pub cancelled: bool,
    pub truncated: bool,
    pub duration_millis: u64,
    pub error_code: Option<ErrorCode>,
    pub error_message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Status {
    pub protocol_version: u32,
    pub available: bool,
    pub backend: SandboxBackend,
    pub mode: SandboxMode,
    pub network_mode: NetworkMode,
    pub degraded: bool,
    pub setup_required: bool,
    pub self_test_passed: bool,
    pub message: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IpcRequest {
    pub protocol_version: u32,
    pub operation: IpcOperation,
    pub execution: Option<ExecutionRequest>,
    pub execution_id: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct IpcResponse {
    pub protocol_version: u32,
    pub operation: IpcOperation,
    pub execution: Option<ExecutionResult>,
    pub status: Option<Status>,
    pub error_code: Option<ErrorCode>,
    pub error_message: Option<String>,
}

impl IpcResponse {
    pub fn protocol_error(message: String) -> Self {
        Self {
            protocol_version: PROTOCOL_VERSION,
            operation: IpcOperation::Status,
            execution: None,
            status: None,
            error_code: Some(ErrorCode::ProtocolError),
            error_message: Some(message),
        }
    }

    pub fn execution_error(execution_id: String, error_code: ErrorCode, message: String) -> Self {
        Self {
            protocol_version: PROTOCOL_VERSION,
            operation: IpcOperation::Execute,
            execution: Some(ExecutionResult {
                protocol_version: PROTOCOL_VERSION,
                execution_id,
                exit_code: None,
                stdout: String::new(),
                stderr: String::new(),
                timed_out: false,
                cancelled: false,
                truncated: false,
                duration_millis: 0,
                error_code: Some(error_code.clone()),
                error_message: Some(message.clone()),
            }),
            status: None,
            error_code: Some(error_code),
            error_message: Some(message),
        }
    }
}
