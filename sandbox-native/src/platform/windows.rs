#[cfg(test)]
use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::ffi::c_void;
use std::fs::{self, File, OpenOptions};
use std::mem::{size_of, zeroed};
use std::os::windows::ffi::OsStrExt;
use std::os::windows::io::AsRawHandle;
use std::path::{Path, PathBuf};
use std::ptr::{null, null_mut};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use serde::Deserialize;
use windows_sys::Win32::Foundation::{
    CloseHandle, GetLastError, SetHandleInformation, ERROR_BROKEN_PIPE, ERROR_NO_DATA,
    ERROR_PIPE_CONNECTED, ERROR_PIPE_LISTENING, GENERIC_READ, GENERIC_WRITE, HANDLE,
    HANDLE_FLAG_INHERIT, INVALID_HANDLE_VALUE, WAIT_OBJECT_0, WAIT_TIMEOUT,
};
use windows_sys::Win32::Security::Authorization::{
    ConvertStringSecurityDescriptorToSecurityDescriptorW, ConvertStringSidToSidW, GetSecurityInfo,
    SDDL_REVISION_1, SE_KERNEL_OBJECT,
};
use windows_sys::Win32::Security::Isolation::{
    CreateAppContainerProfile, DeleteAppContainerProfile, DeriveAppContainerSidFromAppContainerName,
};
use windows_sys::Win32::Security::{
    CreateRestrictedToken, FreeSid, GetLengthSid, GetTokenInformation, IsValidSid, LogonUserW,
    LookupAccountNameW, TokenIsAppContainer, TokenUser, DISABLE_MAX_PRIVILEGE, LOGON32_LOGON_BATCH,
    LOGON32_PROVIDER_DEFAULT, OWNER_SECURITY_INFORMATION, PSECURITY_DESCRIPTOR, PSID,
    SECURITY_ATTRIBUTES, SECURITY_CAPABILITIES, SID_AND_ATTRIBUTES, TOKEN_ASSIGN_PRIMARY,
    TOKEN_DUPLICATE, TOKEN_QUERY, TOKEN_USER, WRITE_RESTRICTED,
};
use windows_sys::Win32::Storage::FileSystem::{
    CreateFileW, ReadFile, WriteFile, FILE_ATTRIBUTE_NORMAL, OPEN_EXISTING, PIPE_ACCESS_DUPLEX,
};
use windows_sys::Win32::System::Environment::{CreateEnvironmentBlock, DestroyEnvironmentBlock};
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
    SetInformationJobObject, TerminateJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
    JOB_OBJECT_LIMIT_ACTIVE_PROCESS, JOB_OBJECT_LIMIT_JOB_MEMORY,
    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE, JOB_OBJECT_LIMIT_PROCESS_MEMORY,
    JOB_OBJECT_LIMIT_PROCESS_TIME,
};
use windows_sys::Win32::System::Pipes::{
    ConnectNamedPipe, CreateNamedPipeW, CreatePipe, GetNamedPipeClientProcessId, PeekNamedPipe,
    WaitNamedPipeW, PIPE_NOWAIT, PIPE_READMODE_BYTE, PIPE_REJECT_REMOTE_CLIENTS, PIPE_TYPE_BYTE,
};
use windows_sys::Win32::System::Threading::{
    CreateProcessAsUserW, CreateProcessWithLogonW, DeleteProcThreadAttributeList,
    GetCurrentProcess, GetExitCodeProcess, InitializeProcThreadAttributeList, OpenProcessToken,
    ResumeThread, TerminateProcess, UpdateProcThreadAttribute, WaitForSingleObject,
    CREATE_NO_WINDOW, CREATE_SUSPENDED, CREATE_UNICODE_ENVIRONMENT, EXTENDED_STARTUPINFO_PRESENT,
    LOGON_WITH_PROFILE, LPPROC_THREAD_ATTRIBUTE_LIST, PROCESS_INFORMATION,
    PROC_THREAD_ATTRIBUTE_HANDLE_LIST, PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES,
    STARTF_USESTDHANDLES, STARTUPINFOEXW, STARTUPINFOW,
};

use crate::protocol::{
    ErrorCode, ExecutionRequest, ExecutionResult, IpcOperation, IpcRequest, IpcResponse,
    NetworkMode, SandboxBackend, SandboxMode, Status, PROTOCOL_VERSION,
};

const SETUP_VERSION: u32 = 6;
const RUNNER_VERSION: u32 = 4;
const MANIFEST_NAME: &str = "setup.json";
const ENTROPY: &[u8] = b"PrometheSandboxV1";
const MAX_TIMEOUT_MILLIS: u64 = 15 * 60 * 1_000;
const MAX_OUTPUT_BYTES: usize = 10 * 1024 * 1024;
const MAX_MEMORY_BYTES: u64 = 16 * 1024 * 1024 * 1024;
const MIN_MEMORY_BYTES: u64 = 16 * 1024 * 1024;
const MAX_PROCESS_LIMIT: u32 = 1_024;
const MAX_ARGUMENTS: usize = 4_096;
const MAX_ARGUMENT_BYTES: usize = 1024 * 1024;
const MAX_ENVIRONMENT_VALUE_BYTES: usize = 8 * 1024;
const POLL_INTERVAL: Duration = Duration::from_millis(10);
const ERROR_INSUFFICIENT_BUFFER: u32 = 122;
const ERROR_PRIVILEGE_NOT_HELD: u32 = 1314;
const HRESULT_ALREADY_EXISTS: i32 = 0x8007_00B7u32 as i32;
const PIPE_BUFFER_BYTES: u32 = 64 * 1024;
const MAX_RUNNER_REQUEST_FRAME_BYTES: usize = 2 * 1024 * 1024;
const MAX_RUNNER_RESPONSE_FRAME_BYTES: usize = 2 * MAX_OUTPUT_BYTES + 1024 * 1024;
const RUNNER_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
const RUNNER_RESPONSE_GRACE: Duration = Duration::from_secs(5);
const NETWORK_CERTIFICATION_TTL: Duration = Duration::from_secs(60);
const RUNNER_MODE_ARGUMENT: &str = "--windows-elevated-runner";
const NETWORK_PROBE_ARGUMENT: &str = "--windows-network-probe";
const UDP_NETWORK_PROBE_ARGUMENT: &str = "--windows-udp-network-probe";
const FILESYSTEM_PROBE_ARGUMENT: &str = "--windows-filesystem-probe";
const CONTROL_CREDENTIAL_PROBE_ARGUMENT: &str = "--windows-control-credential-probe";
const APP_CONTAINER_PROBE_ARGUMENT: &str = "--windows-appcontainer-probe";
const DELETE_APP_CONTAINER_PROFILE_ARGUMENT: &str = "--windows-delete-appcontainer-profile";
const RUNNER_DIAGNOSTIC_ENV: &str = "PROMETHE_SANDBOX_DIAGNOSTIC_PATH";
const FILE_GENERIC_READ: u32 = 0x0012_0089;
const FILE_READ_DATA: u32 = 0x0000_0001;
const FILE_WRITE_DATA: u32 = 0x0000_0002;
const FILE_APPEND_DATA: u32 = 0x0000_0004;
const FILE_WRITE_EA: u32 = 0x0000_0010;
const FILE_DELETE_CHILD: u32 = 0x0000_0040;
const FILE_WRITE_ATTRIBUTES: u32 = 0x0000_0100;
const DELETE_RIGHT: u32 = 0x0001_0000;
const WRITE_DAC_RIGHT: u32 = 0x0004_0000;
const WRITE_OWNER_RIGHT: u32 = 0x0008_0000;
const FILE_WRITE_RIGHTS: u32 =
    FILE_WRITE_DATA | FILE_APPEND_DATA | FILE_WRITE_EA | FILE_WRITE_ATTRIBUTES;
const CONTROL_MUTATION_RIGHTS: u32 =
    FILE_WRITE_RIGHTS | DELETE_RIGHT | WRITE_DAC_RIGHT | WRITE_OWNER_RIGHT;
const DACL_SECURITY_INFORMATION: u32 = 0x0000_0004;
const SE_FILE_OBJECT: u32 = 1;
const TRUSTEE_IS_SID: u32 = 0;
const TRUSTEE_IS_UNKNOWN: u32 = 0;
static ACTIVE_JOBS: OnceLock<Mutex<HashMap<String, isize>>> = OnceLock::new();
static CANCELLED: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();
static SELF_TEST_CERTIFICATION: OnceLock<Mutex<CertificationState>> = OnceLock::new();
static EXECUTION_SERIALIZER: OnceLock<Mutex<()>> = OnceLock::new();
#[cfg(test)]
thread_local! {
    static TEST_SETUP_ROOT: RefCell<Option<PathBuf>> = const { RefCell::new(None) };
}

#[derive(Debug)]
struct SandboxFailure {
    code: ErrorCode,
    message: String,
}

impl SandboxFailure {
    fn new(code: ErrorCode, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }

    fn setup(message: impl Into<String>) -> Self {
        Self::new(ErrorCode::SetupRequired, message)
    }
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SetupManifest {
    setup_version: u32,
    owner_sid: String,
    offline_account: String,
    offline_sid: String,
    online_account: String,
    online_sid: String,
    writer_group: String,
    writer_group_sid: String,
    app_container_name: String,
    app_container_sid: String,
    workspace_roots: Vec<String>,
    offline_credential_file: String,
    online_credential_file: String,
    temp_directory: String,
    runner_file: String,
    runner_version: u32,
}

struct VerifiedSetup {
    root: PathBuf,
    manifest: SetupManifest,
    owner_sid: Vec<u8>,
    offline_sid: Vec<u8>,
    online_sid: Vec<u8>,
    writer_sid: Vec<u8>,
    app_container_sid: Vec<u8>,
}

struct ValidatedExecution {
    request: ExecutionRequest,
    working_directory: PathBuf,
    executable: PathBuf,
    setup: VerifiedSetup,
}

struct OwnedHandle(HANDLE);

impl OwnedHandle {
    fn new(handle: HANDLE) -> Result<Self, SandboxFailure> {
        if handle.is_null() {
            Err(last_error(
                ErrorCode::InternalError,
                "Windows returned an invalid handle",
            ))
        } else {
            Ok(Self(handle))
        }
    }

    fn raw(&self) -> HANDLE {
        self.0
    }

    fn into_raw(mut self) -> HANDLE {
        let handle = self.0;
        self.0 = null_mut();
        handle
    }
}

impl Drop for OwnedHandle {
    fn drop(&mut self) {
        if !self.0.is_null() {
            unsafe {
                CloseHandle(self.0);
            }
        }
    }
}

struct SecretWide(Vec<u16>);

impl SecretWide {
    fn as_slice(&self) -> &[u16] {
        &self.0
    }
}

impl Drop for SecretWide {
    fn drop(&mut self) {
        self.0.iter_mut().for_each(|value| unsafe {
            std::ptr::write_volatile(value, 0);
        });
    }
}

#[derive(Default)]
struct CapturedOutput {
    text: String,
    truncated: bool,
}

#[derive(Default)]
struct CertificationState {
    full_test_passed: bool,
    network_checked_at: Option<Instant>,
}

struct ProcessAttributeList {
    storage: Vec<usize>,
    pointer: LPPROC_THREAD_ATTRIBUTE_LIST,
}

impl ProcessAttributeList {
    fn for_handles_and_capabilities(
        handles: &mut [HANDLE],
        capabilities: &mut SECURITY_CAPABILITIES,
    ) -> Result<Self, SandboxFailure> {
        let mut bytes = 0usize;
        unsafe {
            InitializeProcThreadAttributeList(null_mut(), 2, 0, &mut bytes);
        }
        if bytes == 0 {
            return Err(last_error(
                ErrorCode::BackendUnavailable,
                "Windows sandbox cannot size its inherited-handle policy",
            ));
        }
        let words = bytes.div_ceil(size_of::<usize>());
        let mut storage = vec![0usize; words];
        let pointer = storage.as_mut_ptr() as LPPROC_THREAD_ATTRIBUTE_LIST;
        if unsafe { InitializeProcThreadAttributeList(pointer, 2, 0, &mut bytes) } == 0 {
            return Err(last_error(
                ErrorCode::BackendUnavailable,
                "Windows sandbox cannot initialize its inherited-handle policy",
            ));
        }
        if unsafe {
            UpdateProcThreadAttribute(
                pointer,
                0,
                PROC_THREAD_ATTRIBUTE_HANDLE_LIST as usize,
                handles.as_mut_ptr() as *const c_void,
                std::mem::size_of_val(handles),
                null_mut(),
                null(),
            )
        } == 0
        {
            unsafe {
                DeleteProcThreadAttributeList(pointer);
            }
            return Err(last_error(
                ErrorCode::BackendUnavailable,
                "Windows sandbox cannot restrict inherited handles",
            ));
        }
        if unsafe {
            UpdateProcThreadAttribute(
                pointer,
                0,
                PROC_THREAD_ATTRIBUTE_SECURITY_CAPABILITIES as usize,
                capabilities as *mut SECURITY_CAPABILITIES as *const c_void,
                size_of::<SECURITY_CAPABILITIES>(),
                null_mut(),
                null(),
            )
        } == 0
        {
            unsafe {
                DeleteProcThreadAttributeList(pointer);
            }
            return Err(last_error(
                ErrorCode::BackendUnavailable,
                "Windows sandbox cannot apply AppContainer capabilities",
            ));
        }
        Ok(Self { storage, pointer })
    }
}

impl Drop for ProcessAttributeList {
    fn drop(&mut self) {
        if !self.pointer.is_null() {
            unsafe {
                DeleteProcThreadAttributeList(self.pointer);
            }
        }
        self.storage.fill(0);
    }
}

#[repr(C)]
struct DataBlob {
    size: u32,
    data: *mut u8,
}

#[repr(C)]
struct TrusteeW {
    multiple_trustee: *mut TrusteeW,
    multiple_trustee_operation: u32,
    trustee_form: u32,
    trustee_type: u32,
    name: *mut u16,
}

#[link(name = "crypt32")]
extern "system" {
    fn CryptUnprotectData(
        input: *const DataBlob,
        description: *mut *mut u16,
        entropy: *const DataBlob,
        reserved: *mut c_void,
        prompt: *mut c_void,
        flags: u32,
        output: *mut DataBlob,
    ) -> i32;
}

#[link(name = "advapi32")]
extern "system" {
    fn GetNamedSecurityInfoW(
        object_name: *const u16,
        object_type: u32,
        security_information: u32,
        owner: *mut PSID,
        group: *mut PSID,
        dacl: *mut *mut c_void,
        sacl: *mut *mut c_void,
        security_descriptor: *mut *mut c_void,
    ) -> u32;

    fn GetEffectiveRightsFromAclW(
        acl: *const c_void,
        trustee: *mut TrusteeW,
        access_rights: *mut u32,
    ) -> u32;
}

#[link(name = "kernel32")]
extern "system" {
    fn LocalFree(memory: *mut c_void) -> *mut c_void;
}

pub fn execute(request: IpcRequest) -> IpcResponse {
    let execution_id = request
        .execution
        .as_ref()
        .map(|execution| execution.execution_id.clone())
        .unwrap_or_default();
    if let Err(error) = certify_windows_sandbox(false) {
        return IpcResponse::execution_error(execution_id, error.code, error.message);
    }
    if let Err(error) = verify_active_network_isolation(false) {
        return IpcResponse::execution_error(execution_id, error.code, error.message);
    }
    match validate_execute_request(request) {
        Ok(validated) => match run_broker_execution(validated) {
            Ok(result) => IpcResponse {
                protocol_version: PROTOCOL_VERSION,
                operation: IpcOperation::Execute,
                execution: Some(result),
                status: None,
                error_code: None,
                error_message: None,
            },
            Err(error) => IpcResponse::execution_error(execution_id, error.code, error.message),
        },
        Err(error) => IpcResponse::execution_error(execution_id, error.code, error.message),
    }
}

pub fn cancel(request: IpcRequest) -> IpcResponse {
    if request.execution.is_some() {
        return error_response(
            IpcOperation::Cancel,
            SandboxFailure::new(
                ErrorCode::InvalidRequest,
                "CANCEL must not include an execution payload",
            ),
        );
    }
    let Some(execution_id) = request.execution_id.filter(|value| valid_identifier(value)) else {
        return error_response(
            IpcOperation::Cancel,
            SandboxFailure::new(
                ErrorCode::InvalidRequest,
                "CANCEL requires a valid executionId",
            ),
        );
    };
    if let Ok(mut values) = cancelled().lock() {
        values.insert(execution_id.clone());
    } else {
        return error_response(
            IpcOperation::Cancel,
            SandboxFailure::new(
                ErrorCode::InternalError,
                "cancellation registry is unavailable",
            ),
        );
    }
    let Ok(jobs) = active_jobs().lock() else {
        return error_response(
            IpcOperation::Cancel,
            SandboxFailure::new(
                ErrorCode::InternalError,
                "execution registry is unavailable",
            ),
        );
    };
    if let Some(job) = jobs.get(&execution_id).copied() {
        if unsafe { TerminateJobObject(job as HANDLE, 1) } == 0 {
            return error_response(
                IpcOperation::Cancel,
                last_error(
                    ErrorCode::InternalError,
                    "failed to terminate the Windows sandbox job",
                ),
            );
        }
    }
    drop(jobs);
    IpcResponse {
        protocol_version: PROTOCOL_VERSION,
        operation: IpcOperation::Cancel,
        execution: None,
        status: None,
        error_code: None,
        error_message: None,
    }
}

pub fn status() -> IpcResponse {
    let status = match certify_windows_sandbox(false).and_then(|message| {
        verify_active_network_isolation(true)?;
        Ok(message)
    }) {
        Ok(message) => ready_status(true, Some(&message)),
        Err(error) => unavailable_windows_status(&error),
    };
    IpcResponse {
        protocol_version: PROTOCOL_VERSION,
        operation: IpcOperation::Status,
        execution: None,
        status: Some(status),
        error_code: None,
        error_message: None,
    }
}

pub fn self_test() -> IpcResponse {
    let outcome = certify_windows_sandbox(true);
    let (status, error_code, error_message) = match outcome {
        Ok(message) => (ready_status(true, Some(&message)), None, None),
        Err(error) => {
            let status = unavailable_windows_status(&error);
            (status, Some(error.code), Some(error.message))
        }
    };
    IpcResponse {
        protocol_version: PROTOCOL_VERSION,
        operation: IpcOperation::SelfTest,
        execution: None,
        status: Some(status),
        error_code,
        error_message,
    }
}

pub fn run_special_mode() -> Option<i32> {
    let mut arguments = std::env::args_os();
    let _program = arguments.next();
    let mode = arguments.next()?;
    match mode.to_str()? {
        RUNNER_MODE_ARGUMENT => {
            let pipe_name = arguments.next()?.to_str()?.to_owned();
            if arguments.next().is_some() {
                return Some(2);
            }
            Some(match run_runner_client(&pipe_name) {
                Ok(()) => 0,
                Err(error) => {
                    write_runner_diagnostic(&error.message);
                    eprintln!("Windows sandbox runner failed: {}", error.message);
                    1
                }
            })
        }
        NETWORK_PROBE_ARGUMENT => {
            let address = arguments
                .next()?
                .to_str()?
                .parse::<std::net::SocketAddr>()
                .ok()?;
            if arguments.next().is_some() {
                return Some(2);
            }
            Some(
                if std::net::TcpStream::connect_timeout(&address, Duration::from_secs(2)).is_err() {
                    0
                } else {
                    20
                },
            )
        }
        UDP_NETWORK_PROBE_ARGUMENT => {
            let address = arguments
                .next()?
                .to_str()?
                .parse::<std::net::SocketAddr>()
                .ok()?;
            if arguments.next().is_some() {
                return Some(2);
            }
            let bind_address = if address.is_ipv4() {
                "0.0.0.0:0"
            } else {
                "[::]:0"
            };
            let _ = std::net::UdpSocket::bind(bind_address)
                .and_then(|socket| socket.send_to(b"promethe-network-probe", address));
            Some(0)
        }
        FILESYSTEM_PROBE_ARGUMENT => {
            let workspace = PathBuf::from(arguments.next()?);
            let forbidden = PathBuf::from(arguments.next()?);
            if arguments.next().is_some() {
                return Some(2);
            }
            Some(run_filesystem_probe(&workspace, &forbidden))
        }
        CONTROL_CREDENTIAL_PROBE_ARGUMENT => {
            let offline_credential = PathBuf::from(arguments.next()?);
            let online_credential = PathBuf::from(arguments.next()?);
            if arguments.next().is_some() {
                return Some(2);
            }
            Some(run_control_credential_probe(&[
                offline_credential,
                online_credential,
            ]))
        }
        APP_CONTAINER_PROBE_ARGUMENT => {
            if arguments.next().is_some() {
                return Some(2);
            }
            Some(if current_process_is_app_container() {
                0
            } else {
                26
            })
        }
        DELETE_APP_CONTAINER_PROFILE_ARGUMENT => {
            if arguments.next().is_some() {
                return Some(2);
            }
            let name = wide_string("Promethe.Sandbox.Offline");
            let result = unsafe { DeleteAppContainerProfile(name.as_ptr()) };
            Some(if result >= 0 || result as u32 == 0x8007_0002 {
                0
            } else {
                1
            })
        }
        _ => None,
    }
}

fn run_filesystem_probe(workspace: &Path, forbidden: &Path) -> i32 {
    let read_probe =
        PathBuf::from(std::env::var_os("SystemRoot").unwrap_or_default()).join("win.ini");
    if fs::read(&read_probe).is_err() {
        return 21;
    }
    let marker = format!(
        ".promethe-sandbox-self-test-{}",
        uuid::Uuid::new_v4().simple()
    );
    let workspace_probe = workspace.join(&marker);
    if fs::write(&workspace_probe, b"sandbox-self-test").is_err() {
        return 22;
    }
    let _ = fs::remove_file(&workspace_probe);
    let forbidden_probe = forbidden.join(marker);
    if fs::write(&forbidden_probe, b"sandbox-escape").is_ok() {
        let _ = fs::remove_file(&forbidden_probe);
        return 23;
    }
    0
}

fn run_control_credential_probe(paths: &[PathBuf]) -> i32 {
    for path in paths {
        if fs::read(path).is_ok() {
            return 24;
        }
        if OpenOptions::new().write(true).open(path).is_ok() {
            return 25;
        }
    }
    0
}

fn current_process_is_app_container() -> bool {
    let Ok(token) = current_process_token() else {
        return false;
    };
    let mut value = 0u32;
    let mut returned = 0u32;
    unsafe {
        GetTokenInformation(
            token.raw(),
            TokenIsAppContainer,
            &mut value as *mut u32 as *mut c_void,
            size_of::<u32>() as u32,
            &mut returned,
        ) != 0
            && returned == size_of::<u32>() as u32
            && value != 0
    }
}

fn run_runner_client(pipe_name: &str) -> Result<(), SandboxFailure> {
    if !pipe_name.starts_with(r"\\.\pipe\PrometheSandbox-")
        || pipe_name.len() > 160
        || pipe_name.contains('\0')
    {
        return Err(SandboxFailure::new(
            ErrorCode::InvalidRequest,
            "runner pipe name is invalid",
        ));
    }
    let pipe = open_runner_pipe(pipe_name)?;
    let setup = verify_setup(None)?;
    ensure_app_container_profile(&setup.manifest.app_container_name, &setup.app_container_sid)?;
    verify_pipe_server_identity(pipe.raw(), &setup.owner_sid)?;
    let request_bytes = read_pipe_frame(
        pipe.raw(),
        MAX_RUNNER_REQUEST_FRAME_BYTES,
        Instant::now() + RUNNER_CONNECT_TIMEOUT,
    )?;
    let request: IpcRequest = serde_json::from_slice(&request_bytes)
        .map_err(|_| SandboxFailure::new(ErrorCode::ProtocolError, "runner request is invalid"))?;
    let execution_id = request
        .execution
        .as_ref()
        .map(|execution| execution.execution_id.clone())
        .unwrap_or_default();
    let response = match validate_execute_request(request) {
        Ok(validated) => match run_execution_as_runner(validated) {
            Ok(result) => IpcResponse {
                protocol_version: PROTOCOL_VERSION,
                operation: IpcOperation::Execute,
                execution: Some(result),
                status: None,
                error_code: None,
                error_message: None,
            },
            Err(error) => IpcResponse::execution_error(execution_id, error.code, error.message),
        },
        Err(error) => IpcResponse::execution_error(execution_id, error.code, error.message),
    };
    let response_bytes = serde_json::to_vec(&response).map_err(|_| {
        SandboxFailure::new(
            ErrorCode::InternalError,
            "runner response cannot be encoded",
        )
    })?;
    write_pipe_frame(pipe.raw(), &response_bytes)
}

fn run_broker_execution(
    mut validated: ValidatedExecution,
) -> Result<ExecutionResult, SandboxFailure> {
    let _execution_guard = EXECUTION_SERIALIZER
        .get_or_init(|| Mutex::new(()))
        .lock()
        .map_err(|_| {
            SandboxFailure::new(
                ErrorCode::InternalError,
                "Windows sandbox execution serializer is unavailable",
            )
        })?;
    let broker_token = current_process_token()?;
    verify_process_identity(
        broker_token.raw(),
        &validated.setup.owner_sid,
        "Windows sandbox broker is running under an unexpected owner account",
    )?;
    let execution_id = validated.request.execution_id.clone();
    if cancellation_requested(&execution_id) {
        remove_cancellation(&execution_id);
        return Ok(cancelled_execution(&execution_id));
    }
    validated.request.executable = validated.executable.to_string_lossy().into_owned();
    let runner_path =
        safe_setup_child(&validated.setup.root, &validated.setup.manifest.runner_file)?;
    if !runner_path.is_file() || !executable_extension_allowed(&runner_path) {
        return Err(SandboxFailure::setup(
            "Windows sandbox runner is missing; repair the sandbox setup",
        ));
    }

    let request = IpcRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: IpcOperation::Execute,
        execution: Some(validated.request.clone()),
        execution_id: None,
    };
    let request_bytes = serde_json::to_vec(&request).map_err(|_| {
        SandboxFailure::new(ErrorCode::InternalError, "runner request cannot be encoded")
    })?;
    if request_bytes.len() > MAX_RUNNER_REQUEST_FRAME_BYTES {
        return Err(SandboxFailure::new(
            ErrorCode::InvalidRequest,
            "runner request exceeds the IPC size limit",
        ));
    }

    let (pipe, pipe_name) =
        create_secure_runner_pipe(&validated.setup.owner_sid, &validated.setup.offline_sid)?;
    let credential_path = safe_setup_child(
        &validated.setup.root,
        &validated.setup.manifest.offline_credential_file,
    )?;
    let temp_directory = safe_setup_child(
        &validated.setup.root,
        &validated.setup.manifest.temp_directory,
    )?;
    let diagnostic_path = temp_directory.join(format!(
        "runner-diagnostic-{}.txt",
        uuid::Uuid::new_v4().simple(),
    ));
    let password = decrypt_credential(&credential_path)?;
    let broker_job = create_job(&validated.request)?;
    let mut runner = launch_runner_suspended(
        &runner_path,
        &pipe_name,
        &validated.setup,
        password.as_slice(),
        &diagnostic_path,
    )?;

    if unsafe { AssignProcessToJobObject(broker_job.raw(), runner.hProcess) } == 0 {
        unsafe {
            TerminateProcess(runner.hProcess, 1);
            WaitForSingleObject(runner.hProcess, 5_000);
            CloseHandle(runner.hThread);
            CloseHandle(runner.hProcess);
        }
        return Err(last_error(
            ErrorCode::BackendUnavailable,
            "failed to supervise the Windows sandbox runner",
        ));
    }

    {
        let mut jobs = active_jobs().lock().map_err(|_| {
            SandboxFailure::new(
                ErrorCode::InternalError,
                "execution registry is unavailable",
            )
        })?;
        if jobs.contains_key(&execution_id) {
            unsafe {
                TerminateJobObject(broker_job.raw(), 1);
                CloseHandle(runner.hThread);
                CloseHandle(runner.hProcess);
            }
            return Err(SandboxFailure::new(
                ErrorCode::InvalidRequest,
                "executionId is already active",
            ));
        }
        jobs.insert(execution_id.clone(), broker_job.raw() as isize);
    }

    let mut broker_result = (|| {
        if cancellation_requested(&execution_id) {
            unsafe {
                TerminateJobObject(broker_job.raw(), 1);
            }
            return Err(SandboxFailure::new(
                ErrorCode::Cancelled,
                "sandboxed execution was cancelled",
            ));
        }
        if unsafe { ResumeThread(runner.hThread) } == u32::MAX {
            return Err(last_error(
                ErrorCode::BackendUnavailable,
                "failed to start the Windows sandbox runner",
            ));
        }
        unsafe {
            CloseHandle(runner.hThread);
        }
        runner.hThread = null_mut();
        connect_runner_pipe(pipe.raw(), runner.hProcess, runner.dwProcessId)?;
        write_pipe_frame(pipe.raw(), &request_bytes)?;
        let response_bytes = read_pipe_frame(
            pipe.raw(),
            MAX_RUNNER_RESPONSE_FRAME_BYTES,
            Instant::now()
                + Duration::from_millis(validated.request.profile.limits.timeout_millis)
                + RUNNER_RESPONSE_GRACE,
        )?;
        let response: IpcResponse = serde_json::from_slice(&response_bytes).map_err(|_| {
            SandboxFailure::new(ErrorCode::ProtocolError, "runner response is invalid")
        })?;
        response.execution.ok_or_else(|| {
            SandboxFailure::new(
                response.error_code.unwrap_or(ErrorCode::InternalError),
                response
                    .error_message
                    .unwrap_or_else(|| "runner returned no execution result".into()),
            )
        })
    })();

    if broker_result.is_err() {
        unsafe {
            TerminateJobObject(broker_job.raw(), 1);
        }
    }
    unsafe {
        WaitForSingleObject(runner.hProcess, 5_000);
        if !runner.hThread.is_null() {
            CloseHandle(runner.hThread);
        }
        CloseHandle(runner.hProcess);
    }
    if let Ok(diagnostic) = fs::read_to_string(&diagnostic_path) {
        let diagnostic = diagnostic.trim().replace(['\r', '\n'], " ");
        if !diagnostic.is_empty() {
            if let Err(error) = &mut broker_result {
                error.message = format!("{}: {}", error.message, truncate_text(&diagnostic, 512));
            }
        }
    }
    let _ = fs::remove_file(&diagnostic_path);
    active_jobs()
        .lock()
        .ok()
        .map(|mut jobs| jobs.remove(&execution_id));
    let was_cancelled = cancelled()
        .lock()
        .ok()
        .map(|mut values| values.remove(&execution_id))
        .unwrap_or(false);
    if was_cancelled {
        return Ok(cancelled_execution(&execution_id));
    }
    broker_result
}

fn ready_status(self_test_passed: bool, message: Option<&str>) -> Status {
    Status {
        protocol_version: PROTOCOL_VERSION,
        available: true,
        backend: SandboxBackend::WindowsElevated,
        mode: SandboxMode::WorkspaceWrite,
        network_mode: NetworkMode::Off,
        degraded: false,
        setup_required: false,
        self_test_passed,
        message: message.map(str::to_owned),
    }
}

fn unavailable_windows_status(error: &SandboxFailure) -> Status {
    Status {
        protocol_version: PROTOCOL_VERSION,
        available: false,
        backend: SandboxBackend::WindowsElevated,
        mode: SandboxMode::WorkspaceWrite,
        network_mode: NetworkMode::Off,
        degraded: false,
        setup_required: matches!(error.code, ErrorCode::SetupRequired),
        self_test_passed: false,
        message: Some(error.message.clone()),
    }
}

fn certify_windows_sandbox(force: bool) -> Result<String, SandboxFailure> {
    let mut certified = SELF_TEST_CERTIFICATION
        .get_or_init(|| Mutex::new(CertificationState::default()))
        .lock()
        .map_err(|_| {
            SandboxFailure::new(
                ErrorCode::InternalError,
                "Windows sandbox self-test state is unavailable",
            )
        })?;
    if certified.full_test_passed && !force {
        return Ok("Windows elevated sandbox self-test passed.".into());
    }
    let outcome = run_windows_self_test();
    certified.full_test_passed = outcome.is_ok();
    certified.network_checked_at = outcome.as_ref().ok().map(|_| Instant::now());
    outcome
}

fn run_windows_self_test() -> Result<String, SandboxFailure> {
    let setup = verify_setup(None)?;
    let workspace = setup
        .manifest
        .workspace_roots
        .first()
        .ok_or_else(|| SandboxFailure::setup("Windows sandbox has no registered workspace"))?
        .clone();
    let expected_account = setup.manifest.offline_account.to_ascii_lowercase();
    let runner_path = safe_setup_child(&setup.root, &setup.manifest.runner_file)?;
    let forbidden_root = setup.root.clone();
    let system_root = std::env::var("SystemRoot")
        .map_err(|_| SandboxFailure::setup("SystemRoot is unavailable"))?;
    let whoami = run_self_test_command(
        PathBuf::from(system_root)
            .join("System32")
            .join("whoami.exe"),
        Vec::new(),
        &workspace,
    )?;
    if whoami.exit_code != Some(0)
        || !whoami
            .stdout
            .to_ascii_lowercase()
            .contains(&expected_account)
        || whoami.error_code.is_some()
    {
        return Err(SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            format!(
                "Windows sandbox runner identity self-test failed (exit {:?}, error {:?}: {}, stdout: {}, stderr: {})",
                whoami.exit_code,
                whoami.error_code,
                whoami.error_message.as_deref().unwrap_or("none"),
                truncate_text(whoami.stdout.trim(), 160),
                truncate_text(whoami.stderr.trim(), 160),
            ),
        ));
    }

    let app_container = run_self_test_command(
        runner_path.clone(),
        vec![APP_CONTAINER_PROBE_ARGUMENT.into()],
        &workspace,
    )?;
    if app_container.exit_code != Some(0) || app_container.error_code.is_some() {
        return Err(SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            "Windows sandbox AppContainer self-test failed",
        ));
    }

    let filesystem = run_self_test_command(
        runner_path.clone(),
        vec![
            FILESYSTEM_PROBE_ARGUMENT.into(),
            workspace.clone(),
            forbidden_root.to_string_lossy().into_owned(),
        ],
        &workspace,
    )?;
    if filesystem.exit_code != Some(0) || filesystem.error_code.is_some() {
        return Err(SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            format!(
                "Windows sandbox filesystem self-test failed (exit {:?})",
                filesystem.exit_code
            ),
        ));
    }

    let offline_credential =
        safe_setup_child(&setup.root, &setup.manifest.offline_credential_file)?;
    let online_credential = safe_setup_child(&setup.root, &setup.manifest.online_credential_file)?;
    let credentials = run_self_test_command(
        runner_path.clone(),
        vec![
            CONTROL_CREDENTIAL_PROBE_ARGUMENT.into(),
            offline_credential.to_string_lossy().into_owned(),
            online_credential.to_string_lossy().into_owned(),
        ],
        &workspace,
    )?;
    if credentials.exit_code != Some(0) || credentials.error_code.is_some() {
        return Err(SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            "Windows sandbox credential ACL self-test failed",
        ));
    }

    run_windows_network_self_test(&workspace, &runner_path)?;
    Ok("Windows elevated sandbox self-test passed.".into())
}

fn verify_active_network_isolation(defer_if_active: bool) -> Result<(), SandboxFailure> {
    let mut certification = SELF_TEST_CERTIFICATION
        .get_or_init(|| Mutex::new(CertificationState::default()))
        .lock()
        .map_err(|_| {
            SandboxFailure::new(
                ErrorCode::InternalError,
                "Windows sandbox self-test state is unavailable",
            )
        })?;
    if certification
        .network_checked_at
        .is_some_and(|checked| checked.elapsed() < NETWORK_CERTIFICATION_TTL)
    {
        return Ok(());
    }
    if defer_if_active
        && active_jobs()
            .lock()
            .map(|jobs| !jobs.is_empty())
            .unwrap_or(true)
    {
        return Err(SandboxFailure::new(
            ErrorCode::NetworkDenied,
            "Windows sandbox network certification refresh is pending active execution",
        ));
    }
    let setup = verify_setup(None)?;
    let workspace = setup
        .manifest
        .workspace_roots
        .first()
        .ok_or_else(|| SandboxFailure::setup("Windows sandbox has no registered workspace"))?;
    let runner = safe_setup_child(&setup.root, &setup.manifest.runner_file)?;
    let outcome = run_windows_network_self_test(workspace, &runner);
    if outcome.is_ok() {
        certification.network_checked_at = Some(Instant::now());
    } else {
        certification.full_test_passed = false;
        certification.network_checked_at = None;
    }
    outcome
}

fn run_windows_network_self_test(workspace: &str, runner: &Path) -> Result<(), SandboxFailure> {
    assert_tcp_endpoint_blocked(workspace, runner, "127.0.0.1:0")?;
    if std::net::TcpListener::bind("[::1]:0").is_ok() {
        assert_tcp_endpoint_blocked(workspace, runner, "[::1]:0")?;
    }
    assert_udp_endpoint_blocked(workspace, runner, "127.0.0.1:0")?;
    if std::net::UdpSocket::bind("[::1]:0").is_ok() {
        assert_udp_endpoint_blocked(workspace, runner, "[::1]:0")?;
    }
    if let Some(address) = local_non_loopback_ipv4() {
        assert_tcp_endpoint_blocked(workspace, runner, &format!("{address}:0"))?;
    }
    Ok(())
}

fn assert_tcp_endpoint_blocked(
    workspace: &str,
    runner: &Path,
    bind_address: &str,
) -> Result<(), SandboxFailure> {
    let listener = std::net::TcpListener::bind(bind_address).map_err(|_| {
        SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            "Windows sandbox network self-test cannot create a TCP probe",
        )
    })?;
    let endpoint = listener.local_addr().map_err(|_| {
        SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            "Windows sandbox network self-test cannot inspect a TCP probe",
        )
    })?;
    let network = run_self_test_command(
        runner.to_path_buf(),
        vec![NETWORK_PROBE_ARGUMENT.into(), endpoint.to_string()],
        workspace,
    )?;
    drop(listener);
    if network.exit_code != Some(0) || network.error_code.is_some() {
        return Err(SandboxFailure::new(
            ErrorCode::NetworkDenied,
            "Windows sandbox firewall self-test failed; a TCP endpoint was reachable",
        ));
    }
    Ok(())
}

fn assert_udp_endpoint_blocked(
    workspace: &str,
    runner: &Path,
    bind_address: &str,
) -> Result<(), SandboxFailure> {
    let listener = std::net::UdpSocket::bind(bind_address).map_err(|_| {
        SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            "Windows sandbox network self-test cannot create a UDP probe",
        )
    })?;
    listener
        .set_read_timeout(Some(Duration::from_millis(250)))
        .map_err(|_| {
            SandboxFailure::new(
                ErrorCode::BackendUnavailable,
                "Windows sandbox network self-test cannot configure a UDP probe",
            )
        })?;
    let endpoint = listener.local_addr().map_err(|_| {
        SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            "Windows sandbox network self-test cannot inspect a UDP probe",
        )
    })?;
    let network = run_self_test_command(
        runner.to_path_buf(),
        vec![UDP_NETWORK_PROBE_ARGUMENT.into(), endpoint.to_string()],
        workspace,
    )?;
    if network.exit_code != Some(0) || network.error_code.is_some() {
        return Err(SandboxFailure::new(
            ErrorCode::NetworkDenied,
            "Windows sandbox UDP probe could not execute",
        ));
    }
    let mut buffer = [0u8; 64];
    if listener.recv_from(&mut buffer).is_ok() {
        return Err(SandboxFailure::new(
            ErrorCode::NetworkDenied,
            "Windows sandbox firewall self-test failed; a UDP endpoint was reachable",
        ));
    }
    Ok(())
}

fn local_non_loopback_ipv4() -> Option<std::net::Ipv4Addr> {
    let socket = std::net::UdpSocket::bind("0.0.0.0:0").ok()?;
    socket.connect("192.0.2.1:9").ok()?;
    match socket.local_addr().ok()?.ip() {
        std::net::IpAddr::V4(address) if !address.is_loopback() && !address.is_unspecified() => {
            Some(address)
        }
        _ => None,
    }
}

fn run_self_test_command(
    executable: PathBuf,
    arguments: Vec<String>,
    workspace: &str,
) -> Result<ExecutionResult, SandboxFailure> {
    let request = IpcRequest {
        protocol_version: PROTOCOL_VERSION,
        operation: IpcOperation::Execute,
        execution: Some(ExecutionRequest {
            protocol_version: PROTOCOL_VERSION,
            execution_id: format!("self-test-{}", uuid::Uuid::new_v4().simple()),
            session_id: "sandbox-self-test".into(),
            executable: executable.to_string_lossy().into_owned(),
            arguments,
            working_directory: workspace.to_owned(),
            environment: Default::default(),
            sensitive_environment_keys: Default::default(),
            profile: crate::protocol::PermissionProfile {
                mode: SandboxMode::WorkspaceWrite,
                approval_policy: crate::protocol::ApprovalPolicy::OnRequest,
                network_mode: NetworkMode::Off,
                readable_roots: vec![workspace.to_owned()],
                writable_roots: vec![workspace.to_owned()],
                protected_paths: vec![
                    ".git".into(),
                    ".promethe".into(),
                    ".codex".into(),
                    ".agents".into(),
                ],
                allowed_domains: Vec::new(),
                limits: crate::protocol::ResourceLimits {
                    timeout_millis: 10_000,
                    max_output_bytes_per_stream: 16 * 1024,
                    memory_bytes: 128 * 1024 * 1024,
                    cpu_limit: 1.0,
                    process_limit: 8,
                },
            },
            interactive: false,
        }),
        execution_id: None,
    };
    let validated = validate_execute_request(request)?;
    run_broker_execution(validated)
}

fn create_secure_runner_pipe(
    owner_sid: &[u8],
    offline_sid: &[u8],
) -> Result<(OwnedHandle, String), SandboxFailure> {
    let pipe_name = format!(
        r"\\.\pipe\PrometheSandbox-{}",
        uuid::Uuid::new_v4().simple()
    );
    let owner_sid = sid_string(owner_sid)?;
    let sddl = format!(
        "O:{owner_sid}D:P(A;;GA;;;SY)(A;;GA;;;BA)(A;;GA;;;{owner_sid})(A;;GA;;;{})",
        sid_string(offline_sid)?,
    );
    let sddl_wide = wide_string(&sddl);
    let mut descriptor: PSECURITY_DESCRIPTOR = null_mut();
    let converted = unsafe {
        ConvertStringSecurityDescriptorToSecurityDescriptorW(
            sddl_wide.as_ptr(),
            SDDL_REVISION_1,
            &mut descriptor,
            null_mut(),
        )
    };
    if converted == 0 || descriptor.is_null() {
        return Err(last_error(
            ErrorCode::BackendUnavailable,
            "failed to secure the Windows sandbox runner pipe",
        ));
    }
    let attributes = SECURITY_ATTRIBUTES {
        nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
        lpSecurityDescriptor: descriptor,
        bInheritHandle: 0,
    };
    let pipe_name_wide = wide_string(&pipe_name);
    let handle = unsafe {
        CreateNamedPipeW(
            pipe_name_wide.as_ptr(),
            PIPE_ACCESS_DUPLEX,
            PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_NOWAIT | PIPE_REJECT_REMOTE_CLIENTS,
            1,
            PIPE_BUFFER_BYTES,
            PIPE_BUFFER_BYTES,
            RUNNER_CONNECT_TIMEOUT.as_millis() as u32,
            &attributes,
        )
    };
    unsafe {
        LocalFree(descriptor);
    }
    if handle == INVALID_HANDLE_VALUE || handle.is_null() {
        return Err(last_error(
            ErrorCode::BackendUnavailable,
            "failed to create the Windows sandbox runner pipe",
        ));
    }
    Ok((OwnedHandle(handle), pipe_name))
}

fn connect_runner_pipe(
    pipe: HANDLE,
    runner_process: HANDLE,
    runner_process_id: u32,
) -> Result<(), SandboxFailure> {
    let deadline = Instant::now() + RUNNER_CONNECT_TIMEOUT;
    loop {
        if unsafe { ConnectNamedPipe(pipe, null_mut()) } != 0 {
            return verify_runner_pipe_client(pipe, runner_process_id);
        }
        let error = unsafe { GetLastError() };
        if error == ERROR_PIPE_CONNECTED {
            return verify_runner_pipe_client(pipe, runner_process_id);
        }
        if error != ERROR_PIPE_LISTENING && error != ERROR_NO_DATA {
            return Err(SandboxFailure::new(
                ErrorCode::BackendUnavailable,
                format!("Windows sandbox runner pipe connection failed (Win32 {error})"),
            ));
        }
        if unsafe { WaitForSingleObject(runner_process, 0) } == WAIT_OBJECT_0 {
            return Err(SandboxFailure::new(
                ErrorCode::BackendUnavailable,
                "Windows sandbox runner exited before connecting",
            ));
        }
        if Instant::now() >= deadline {
            return Err(SandboxFailure::new(
                ErrorCode::TimedOut,
                "Windows sandbox runner did not connect in time",
            ));
        }
        std::thread::sleep(POLL_INTERVAL);
    }
}

fn verify_runner_pipe_client(pipe: HANDLE, expected_process_id: u32) -> Result<(), SandboxFailure> {
    let mut process_id = 0u32;
    if unsafe { GetNamedPipeClientProcessId(pipe, &mut process_id) } == 0
        || process_id != expected_process_id
    {
        return Err(SandboxFailure::new(
            ErrorCode::PolicyDenied,
            "Windows sandbox broker rejected an unexpected runner client",
        ));
    }
    Ok(())
}

fn open_runner_pipe(pipe_name: &str) -> Result<OwnedHandle, SandboxFailure> {
    let pipe_name = wide_string(pipe_name);
    if unsafe {
        WaitNamedPipeW(
            pipe_name.as_ptr(),
            RUNNER_CONNECT_TIMEOUT.as_millis() as u32,
        )
    } == 0
    {
        return Err(last_error(
            ErrorCode::BackendUnavailable,
            "Windows sandbox broker pipe is unavailable",
        ));
    }
    let handle = unsafe {
        CreateFileW(
            pipe_name.as_ptr(),
            GENERIC_READ | GENERIC_WRITE,
            0,
            null(),
            OPEN_EXISTING,
            FILE_ATTRIBUTE_NORMAL,
            null_mut(),
        )
    };
    if handle == INVALID_HANDLE_VALUE || handle.is_null() {
        return Err(last_error(
            ErrorCode::BackendUnavailable,
            "Windows sandbox runner cannot connect to its broker",
        ));
    }
    Ok(OwnedHandle(handle))
}

fn verify_pipe_server_identity(
    pipe: HANDLE,
    expected_owner_sid: &[u8],
) -> Result<(), SandboxFailure> {
    let mut owner: PSID = null_mut();
    let mut descriptor: PSECURITY_DESCRIPTOR = null_mut();
    let result = unsafe {
        GetSecurityInfo(
            pipe,
            SE_KERNEL_OBJECT,
            OWNER_SECURITY_INFORMATION,
            &mut owner,
            null_mut(),
            null_mut(),
            null_mut(),
            &mut descriptor,
        )
    };
    if result != 0 || owner.is_null() || unsafe { IsValidSid(owner) } == 0 {
        if !descriptor.is_null() {
            unsafe {
                LocalFree(descriptor);
            }
        }
        return Err(SandboxFailure::new(
            ErrorCode::PolicyDenied,
            "Windows sandbox runner cannot authenticate its broker",
        ));
    }
    let owner_length = unsafe { GetLengthSid(owner) } as usize;
    let actual_owner = unsafe { std::slice::from_raw_parts(owner as *const u8, owner_length) };
    let matches = actual_owner == expected_owner_sid;
    unsafe {
        LocalFree(descriptor);
    }
    if !matches {
        return Err(SandboxFailure::new(
            ErrorCode::PolicyDenied,
            "Windows sandbox runner rejected an untrusted broker",
        ));
    }
    Ok(())
}

fn write_pipe_frame(handle: HANDLE, payload: &[u8]) -> Result<(), SandboxFailure> {
    if payload.len() > u32::MAX as usize {
        return Err(SandboxFailure::new(
            ErrorCode::ProtocolError,
            "runner IPC frame is too large",
        ));
    }
    write_pipe_bytes(handle, &(payload.len() as u32).to_le_bytes())?;
    write_pipe_bytes(handle, payload)
}

fn write_pipe_bytes(handle: HANDLE, mut bytes: &[u8]) -> Result<(), SandboxFailure> {
    while !bytes.is_empty() {
        let mut written = 0u32;
        let chunk = bytes.len().min(u32::MAX as usize) as u32;
        let success = unsafe { WriteFile(handle, bytes.as_ptr(), chunk, &mut written, null_mut()) };
        if success == 0 || written == 0 {
            return Err(last_error(
                ErrorCode::BackendUnavailable,
                "Windows sandbox runner pipe write failed",
            ));
        }
        bytes = &bytes[written as usize..];
    }
    Ok(())
}

fn read_pipe_frame(
    handle: HANDLE,
    max_bytes: usize,
    deadline: Instant,
) -> Result<Vec<u8>, SandboxFailure> {
    let mut header = [0u8; 4];
    read_pipe_exact(handle, &mut header, deadline)?;
    let length = u32::from_le_bytes(header) as usize;
    if length == 0 || length > max_bytes {
        return Err(SandboxFailure::new(
            ErrorCode::ProtocolError,
            "runner IPC frame has an invalid size",
        ));
    }
    let mut payload = vec![0u8; length];
    read_pipe_exact(handle, &mut payload, deadline)?;
    Ok(payload)
}

fn read_pipe_exact(
    handle: HANDLE,
    mut output: &mut [u8],
    deadline: Instant,
) -> Result<(), SandboxFailure> {
    while !output.is_empty() {
        let mut available = 0u32;
        let peeked = unsafe {
            PeekNamedPipe(
                handle,
                null_mut(),
                0,
                null_mut(),
                &mut available,
                null_mut(),
            )
        };
        if peeked == 0 {
            let error = unsafe { GetLastError() };
            if error == ERROR_BROKEN_PIPE {
                return Err(SandboxFailure::new(
                    ErrorCode::BackendUnavailable,
                    "Windows sandbox runner disconnected",
                ));
            }
            return Err(SandboxFailure::new(
                ErrorCode::BackendUnavailable,
                format!("Windows sandbox runner pipe read failed (Win32 {error})"),
            ));
        }
        if available == 0 {
            if Instant::now() >= deadline {
                return Err(SandboxFailure::new(
                    ErrorCode::TimedOut,
                    "Windows sandbox runner response timed out",
                ));
            }
            std::thread::sleep(POLL_INTERVAL);
            continue;
        }
        let mut read = 0u32;
        let amount = output.len().min(available as usize).min(u32::MAX as usize) as u32;
        let success =
            unsafe { ReadFile(handle, output.as_mut_ptr(), amount, &mut read, null_mut()) };
        if success == 0 || read == 0 {
            return Err(last_error(
                ErrorCode::BackendUnavailable,
                "Windows sandbox runner pipe read failed",
            ));
        }
        let (_, remaining) = output.split_at_mut(read as usize);
        output = remaining;
    }
    Ok(())
}

fn launch_runner_suspended(
    runner_path: &Path,
    pipe_name: &str,
    setup: &VerifiedSetup,
    password: &[u16],
    diagnostic_path: &Path,
) -> Result<PROCESS_INFORMATION, SandboxFailure> {
    let username = wide_string(&setup.manifest.offline_account);
    let domain = wide_string(".");
    let mut password_z = password.to_vec();
    password_z.push(0);
    let executable = wide(runner_path.as_os_str());
    let mut command_line = wide_string(&format!(
        "{} {} {}",
        quote_windows_argument(&runner_path.to_string_lossy()),
        RUNNER_MODE_ARGUMENT,
        quote_windows_argument(pipe_name),
    ));
    let working_directory = wide(setup.root.as_os_str());
    let environment = build_runner_environment_block(setup, diagnostic_path)?;
    let mut startup: STARTUPINFOW = unsafe { zeroed() };
    startup.cb = size_of::<STARTUPINFOW>() as u32;
    let mut process: PROCESS_INFORMATION = unsafe { zeroed() };
    let created = unsafe {
        CreateProcessWithLogonW(
            username.as_ptr(),
            domain.as_ptr(),
            password_z.as_ptr(),
            LOGON_WITH_PROFILE,
            executable.as_ptr(),
            command_line.as_mut_ptr(),
            CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW,
            environment.as_ptr() as *const c_void,
            working_directory.as_ptr(),
            &startup,
            &mut process,
        )
    };
    password_z.fill(0);
    if created == 0 {
        return Err(last_error(
            ErrorCode::SetupRequired,
            "failed to launch the dedicated Windows sandbox runner",
        ));
    }
    Ok(process)
}

fn build_runner_environment_block(
    setup: &VerifiedSetup,
    diagnostic_path: &Path,
) -> Result<Vec<u16>, SandboxFailure> {
    let mut environment = std::collections::BTreeMap::new();
    let system_root = std::env::var("SystemRoot")
        .map_err(|_| SandboxFailure::setup("SystemRoot is unavailable"))?;
    let program_data = std::env::var("ProgramData")
        .map_err(|_| SandboxFailure::setup("ProgramData is unavailable"))?;
    let temp = safe_setup_child(&setup.root, &setup.manifest.temp_directory)?;
    environment.insert("PATH", format!("{system_root}\\System32"));
    environment.insert("ProgramData", program_data);
    environment.insert(
        RUNNER_DIAGNOSTIC_ENV,
        diagnostic_path.to_string_lossy().into_owned(),
    );
    environment.insert("SystemRoot", system_root.clone());
    environment.insert("WINDIR", system_root);
    environment.insert("TEMP", temp.to_string_lossy().into_owned());
    environment.insert("TMP", temp.to_string_lossy().into_owned());
    let mut block = Vec::new();
    for (key, value) in environment {
        block.extend(format!("{key}={value}").encode_utf16());
        block.push(0);
    }
    block.push(0);
    Ok(block)
}

fn write_runner_diagnostic(message: &str) {
    let Some(path) = std::env::var_os(RUNNER_DIAGNOSTIC_ENV) else {
        return;
    };
    let _ = fs::write(path, truncate_text(message, 512));
}

fn truncate_text(value: &str, max_chars: usize) -> String {
    value.chars().take(max_chars).collect()
}

fn validate_execute_request(request: IpcRequest) -> Result<ValidatedExecution, SandboxFailure> {
    if request.execution_id.is_some() {
        return Err(SandboxFailure::new(
            ErrorCode::InvalidRequest,
            "EXECUTE must not include a top-level executionId",
        ));
    }
    let execution = request.execution.ok_or_else(|| {
        SandboxFailure::new(
            ErrorCode::InvalidRequest,
            "EXECUTE requires an execution payload",
        )
    })?;
    if execution.protocol_version != PROTOCOL_VERSION {
        return Err(SandboxFailure::new(
            ErrorCode::ProtocolError,
            "execution payload uses an unsupported protocol version",
        ));
    }
    validate_identifier("executionId", &execution.execution_id)?;
    validate_identifier("sessionId", &execution.session_id)?;
    validate_profile(&execution)?;
    validate_command(&execution)?;

    let working_directory =
        canonical_directory(Path::new(&execution.working_directory), "working directory")?;
    let mut requested_roots = canonical_roots(&execution.profile.readable_roots)?;
    requested_roots.extend(canonical_roots(&execution.profile.writable_roots)?);
    if requested_roots.is_empty() || !is_within_any(&working_directory, &requested_roots) {
        return Err(SandboxFailure::new(
            ErrorCode::WorkspaceViolation,
            "working directory is outside the requested workspace roots",
        ));
    }
    let setup = verify_setup(Some(&requested_roots))?;
    let executable = resolve_executable(&execution.executable)?;

    Ok(ValidatedExecution {
        request: execution,
        working_directory,
        executable,
        setup,
    })
}

fn validate_profile(execution: &ExecutionRequest) -> Result<(), SandboxFailure> {
    let profile = &execution.profile;
    if !matches!(profile.network_mode, NetworkMode::Off) || !profile.allowed_domains.is_empty() {
        return Err(SandboxFailure::new(
            ErrorCode::NetworkDenied,
            "Windows native execution only supports network mode OFF",
        ));
    }
    if matches!(profile.mode, SandboxMode::FullAccess) {
        return Err(SandboxFailure::new(
            ErrorCode::PolicyDenied,
            "FULL_ACCESS is not a sandboxed execution mode",
        ));
    }
    if matches!(profile.mode, SandboxMode::ReadOnly) && !profile.writable_roots.is_empty() {
        return Err(SandboxFailure::new(
            ErrorCode::PolicyDenied,
            "READ_ONLY cannot declare writable roots",
        ));
    }
    let limits = &profile.limits;
    if !(1..=MAX_TIMEOUT_MILLIS).contains(&limits.timeout_millis)
        || !(1..=MAX_OUTPUT_BYTES).contains(&limits.max_output_bytes_per_stream)
        || !(MIN_MEMORY_BYTES..=MAX_MEMORY_BYTES).contains(&limits.memory_bytes)
        || !limits.cpu_limit.is_finite()
        || !(0.0..=64.0).contains(&limits.cpu_limit)
        || limits.cpu_limit == 0.0
        || !(1..=MAX_PROCESS_LIMIT).contains(&limits.process_limit)
    {
        return Err(SandboxFailure::new(
            ErrorCode::ResourceLimit,
            "one or more resource limits are outside the supported range",
        ));
    }
    Ok(())
}

fn validate_command(execution: &ExecutionRequest) -> Result<(), SandboxFailure> {
    validate_text("executable", &execution.executable, 4_096)?;
    crate::command_policy::validate_direct_command(&execution.executable, &execution.arguments)
        .map_err(|message| SandboxFailure::new(ErrorCode::PolicyDenied, message))?;
    if execution.arguments.len() > MAX_ARGUMENTS {
        return Err(SandboxFailure::new(
            ErrorCode::InvalidRequest,
            "too many command arguments",
        ));
    }
    let mut bytes = 0usize;
    for argument in &execution.arguments {
        validate_text("argument", argument, 64 * 1024)?;
        bytes = bytes.saturating_add(argument.len());
    }
    if bytes > MAX_ARGUMENT_BYTES {
        return Err(SandboxFailure::new(
            ErrorCode::InvalidRequest,
            "command arguments exceed the supported size",
        ));
    }
    if execution.interactive {
        return Err(SandboxFailure::new(
            ErrorCode::PolicyDenied,
            "interactive execution is unavailable in protocol version 1",
        ));
    }
    if !execution.sensitive_environment_keys.is_empty() {
        return Err(SandboxFailure::new(
            ErrorCode::PolicyDenied,
            "sensitive environment values are forbidden in the Windows sandbox",
        ));
    }
    for (key, value) in &execution.environment {
        if !allowed_environment_key(key) || looks_sensitive(key) {
            return Err(SandboxFailure::new(
                ErrorCode::PolicyDenied,
                format!("environment variable {key} is not allowed"),
            ));
        }
        validate_text("environment value", value, MAX_ENVIRONMENT_VALUE_BYTES)?;
    }
    Ok(())
}

fn verify_setup(requested_roots: Option<&[PathBuf]>) -> Result<VerifiedSetup, SandboxFailure> {
    let root = setup_root()?;
    let manifest_path = root.join(MANIFEST_NAME);
    let manifest_bytes = fs::read(&manifest_path)
        .map_err(|_| SandboxFailure::setup("elevated Windows sandbox setup is missing"))?;
    let manifest: SetupManifest = serde_json::from_slice(&manifest_bytes)
        .map_err(|_| SandboxFailure::setup("Windows sandbox setup manifest is invalid"))?;
    if manifest.setup_version != SETUP_VERSION {
        return Err(SandboxFailure::setup(
            "Windows sandbox setup version is unsupported",
        ));
    }
    if manifest.runner_version != RUNNER_VERSION {
        return Err(SandboxFailure::setup(
            "Windows sandbox runner version is outdated; repair the sandbox setup",
        ));
    }
    validate_manifest_name(&manifest.offline_account)?;
    validate_manifest_name(&manifest.online_account)?;
    validate_manifest_name(&manifest.writer_group)?;
    validate_text("AppContainer name", &manifest.app_container_name, 128)?;
    if !manifest.app_container_sid.starts_with("S-1-15-2-") {
        return Err(SandboxFailure::setup(
            "Windows sandbox AppContainer SID is invalid",
        ));
    }

    let owner_sid = parse_sid(&manifest.owner_sid)?;
    let offline_sid = lookup_account_sid(&manifest.offline_account)?;
    let online_sid = lookup_account_sid(&manifest.online_account)?;
    let writer_sid = lookup_account_sid(&manifest.writer_group)?;
    let app_container_sid = parse_sid(&manifest.app_container_sid)?;
    if sid_string(&offline_sid)? != manifest.offline_sid
        || sid_string(&online_sid)? != manifest.online_sid
        || sid_string(&writer_sid)? != manifest.writer_group_sid
    {
        return Err(SandboxFailure::setup(
            "Windows sandbox account identity does not match the elevated setup",
        ));
    }

    let workspace_roots = canonical_roots(&manifest.workspace_roots)?;
    if workspace_roots.len() != 1 {
        return Err(SandboxFailure::setup(
            "Windows sandbox setup must register exactly one workspace",
        ));
    }
    if let Some(requested) = requested_roots {
        for root in requested {
            if !is_within_any(root, &workspace_roots) {
                return Err(SandboxFailure::setup(
                    "requested workspace is not registered by elevated setup",
                ));
            }
            verify_effective_rights(root, &offline_sid, FILE_GENERIC_READ)?;
            verify_effective_rights(root, &writer_sid, FILE_WRITE_RIGHTS)?;
            verify_effective_rights(root, &app_container_sid, FILE_GENERIC_READ)?;
            verify_effective_rights(root, &app_container_sid, FILE_WRITE_RIGHTS)?;
            verify_effective_rights_absent(root, &writer_sid, FILE_DELETE_CHILD)?;
            verify_protected_workspace_paths(
                root,
                &offline_sid,
                &online_sid,
                &writer_sid,
                &app_container_sid,
            )?;
        }
    } else {
        for workspace in &workspace_roots {
            verify_effective_rights(workspace, &offline_sid, FILE_GENERIC_READ)?;
            verify_effective_rights(workspace, &writer_sid, FILE_WRITE_RIGHTS)?;
            verify_effective_rights(workspace, &app_container_sid, FILE_GENERIC_READ)?;
            verify_effective_rights(workspace, &app_container_sid, FILE_WRITE_RIGHTS)?;
            verify_effective_rights_absent(workspace, &writer_sid, FILE_DELETE_CHILD)?;
            verify_protected_workspace_paths(
                workspace,
                &offline_sid,
                &online_sid,
                &writer_sid,
                &app_container_sid,
            )?;
        }
    }

    let offline_credential = safe_setup_child(&root, &manifest.offline_credential_file)?;
    let online_credential = safe_setup_child(&root, &manifest.online_credential_file)?;
    let temp_directory = safe_setup_child(&root, &manifest.temp_directory)?;
    let runner = safe_setup_child(&root, &manifest.runner_file)?;
    let manifest_path = root.join(MANIFEST_NAME);
    if !offline_credential.is_file()
        || !online_credential.is_file()
        || !temp_directory.is_dir()
        || !runner.is_file()
    {
        return Err(SandboxFailure::setup(
            "Windows sandbox credentials, runner or temporary directory are missing",
        ));
    }
    verify_effective_rights(&runner, &offline_sid, FILE_GENERIC_READ)?;
    verify_effective_rights(&runner, &app_container_sid, FILE_GENERIC_READ)?;
    verify_effective_rights(&temp_directory, &offline_sid, FILE_WRITE_RIGHTS)?;
    verify_effective_rights(&temp_directory, &app_container_sid, FILE_WRITE_RIGHTS)?;
    for sid in [&offline_sid, &online_sid, &writer_sid] {
        verify_effective_rights_absent(
            &offline_credential,
            sid,
            FILE_READ_DATA | CONTROL_MUTATION_RIGHTS,
        )?;
        verify_effective_rights_absent(
            &online_credential,
            sid,
            FILE_READ_DATA | CONTROL_MUTATION_RIGHTS,
        )?;
        verify_effective_rights_absent(&manifest_path, sid, CONTROL_MUTATION_RIGHTS)?;
        verify_effective_rights_absent(&runner, sid, CONTROL_MUTATION_RIGHTS)?;
    }
    verify_effective_rights_absent(
        &offline_credential,
        &app_container_sid,
        FILE_READ_DATA | CONTROL_MUTATION_RIGHTS,
    )?;
    verify_effective_rights_absent(
        &online_credential,
        &app_container_sid,
        FILE_READ_DATA | CONTROL_MUTATION_RIGHTS,
    )?;
    verify_effective_rights_absent(&manifest_path, &app_container_sid, CONTROL_MUTATION_RIGHTS)?;
    verify_effective_rights_absent(&runner, &app_container_sid, CONTROL_MUTATION_RIGHTS)?;

    Ok(VerifiedSetup {
        root,
        manifest,
        owner_sid,
        offline_sid,
        online_sid,
        writer_sid,
        app_container_sid,
    })
}

fn verify_runtime_identities(setup: &VerifiedSetup) -> Result<(), SandboxFailure> {
    for (account, credential, sid) in [
        (
            setup.manifest.offline_account.as_str(),
            setup.manifest.offline_credential_file.as_str(),
            setup.offline_sid.as_slice(),
        ),
        (
            setup.manifest.online_account.as_str(),
            setup.manifest.online_credential_file.as_str(),
            setup.online_sid.as_slice(),
        ),
    ] {
        let credential_path = safe_setup_child(&setup.root, credential)?;
        let password = decrypt_credential(&credential_path)?;
        let token = logon_account(account, password.as_slice())?;
        let _restricted = create_restricted_token(token.raw(), sid, Some(&setup.writer_sid))?;
    }
    Ok(())
}

fn run_execution_as_runner(
    validated: ValidatedExecution,
) -> Result<ExecutionResult, SandboxFailure> {
    let execution_id = validated.request.execution_id.clone();
    let current_token = current_process_token()?;
    verify_runner_identity(current_token.raw(), &validated.setup.offline_sid)?;
    let restricted_token = create_restricted_token(
        current_token.raw(),
        &validated.setup.offline_sid,
        if matches!(&validated.request.profile.mode, SandboxMode::WorkspaceWrite) {
            Some(&validated.setup.writer_sid)
        } else {
            None
        },
    )?;

    let stdin_file = OpenOptions::new().read(true).open("NUL").map_err(|error| {
        SandboxFailure::new(
            ErrorCode::InternalError,
            format!("failed to open null input: {error}"),
        )
    })?;
    set_inheritable(&stdin_file)?;
    let (stdout_read, stdout_write) = create_output_pipe()?;
    let (stderr_read, stderr_write) = create_output_pipe()?;
    let output_limit_hit = Arc::new(AtomicBool::new(false));
    let retained_limit = validated.request.profile.limits.max_output_bytes_per_stream;
    let hard_limit = retained_limit.saturating_mul(2);
    let stdout_reader = spawn_output_reader(
        stdout_read,
        retained_limit,
        hard_limit,
        Arc::clone(&output_limit_hit),
    );
    let stderr_reader = spawn_output_reader(
        stderr_read,
        retained_limit,
        hard_limit,
        Arc::clone(&output_limit_hit),
    );

    let job = create_job(&validated.request)?;
    let started = Instant::now();
    let process_result = spawn_suspended(
        &validated,
        restricted_token.raw(),
        stdin_file.as_raw_handle() as HANDLE,
        stdout_write.raw(),
        stderr_write.raw(),
    );
    drop(stdout_write);
    drop(stderr_write);
    let mut process = match process_result {
        Ok(process) => process,
        Err(error) => {
            let _ = stdout_reader.join();
            let _ = stderr_reader.join();
            return Err(error);
        }
    };
    if unsafe { AssignProcessToJobObject(job.raw(), process.hProcess) } == 0 {
        unsafe {
            TerminateProcess(process.hProcess, 1);
            WaitForSingleObject(process.hProcess, 5_000);
            CloseHandle(process.hThread);
            CloseHandle(process.hProcess);
        }
        return Err(last_error(
            ErrorCode::InternalError,
            "failed to assign child to the Windows Job Object",
        ));
    }
    if unsafe { ResumeThread(process.hThread) } == u32::MAX {
        unsafe {
            TerminateJobObject(job.raw(), 1);
            CloseHandle(process.hThread);
            CloseHandle(process.hProcess);
        }
        return Err(last_error(
            ErrorCode::InternalError,
            "failed to resume sandboxed child",
        ));
    }
    unsafe {
        CloseHandle(process.hThread);
    }
    process.hThread = null_mut();

    let mut timed_out = false;
    let mut exceeded_output_limit = false;
    loop {
        let wait =
            unsafe { WaitForSingleObject(process.hProcess, POLL_INTERVAL.as_millis() as u32) };
        if wait == WAIT_OBJECT_0 {
            break;
        }
        if wait != WAIT_TIMEOUT {
            unsafe {
                TerminateJobObject(job.raw(), 1);
            }
            break;
        }
        if started.elapsed().as_millis() as u64 >= validated.request.profile.limits.timeout_millis {
            timed_out = true;
            unsafe {
                TerminateJobObject(job.raw(), 1);
            }
            break;
        }
        if output_limit_hit.load(Ordering::Acquire) {
            exceeded_output_limit = true;
            unsafe {
                TerminateJobObject(job.raw(), 1);
            }
            break;
        }
    }
    unsafe {
        TerminateJobObject(job.raw(), 1);
    }
    unsafe {
        WaitForSingleObject(process.hProcess, 5_000);
    }

    let mut exit_code = 1u32;
    let got_exit = unsafe { GetExitCodeProcess(process.hProcess, &mut exit_code) } != 0;
    unsafe {
        CloseHandle(process.hProcess);
    }
    let was_cancelled = false;

    let stdout = stdout_reader.join().unwrap_or_default();
    let stderr = stderr_reader.join().unwrap_or_default();
    exceeded_output_limit |= output_limit_hit.load(Ordering::Acquire);

    Ok(ExecutionResult {
        protocol_version: PROTOCOL_VERSION,
        execution_id,
        exit_code: got_exit.then_some(exit_code as i32),
        stdout: stdout.text,
        stderr: stderr.text,
        timed_out,
        cancelled: was_cancelled,
        truncated: exceeded_output_limit || stdout.truncated || stderr.truncated,
        duration_millis: started.elapsed().as_millis() as u64,
        error_code: if timed_out {
            Some(ErrorCode::TimedOut)
        } else if was_cancelled {
            Some(ErrorCode::Cancelled)
        } else if exceeded_output_limit {
            Some(ErrorCode::ResourceLimit)
        } else {
            None
        },
        error_message: if timed_out {
            Some("sandboxed execution timed out".into())
        } else if was_cancelled {
            Some("sandboxed execution was cancelled".into())
        } else if exceeded_output_limit {
            Some("sandboxed execution exceeded the output limit".into())
        } else {
            None
        },
    })
}

fn spawn_suspended(
    validated: &ValidatedExecution,
    token: HANDLE,
    stdin_handle: HANDLE,
    stdout_handle: HANDLE,
    stderr_handle: HANDLE,
) -> Result<PROCESS_INFORMATION, SandboxFailure> {
    let mut inherited_handles = [stdin_handle, stdout_handle, stderr_handle];
    let mut security_capabilities = SECURITY_CAPABILITIES {
        AppContainerSid: validated.setup.app_container_sid.as_ptr() as PSID,
        Capabilities: null_mut(),
        CapabilityCount: 0,
        Reserved: 0,
    };
    let attributes = ProcessAttributeList::for_handles_and_capabilities(
        &mut inherited_handles,
        &mut security_capabilities,
    )?;
    let mut startup: STARTUPINFOEXW = unsafe { zeroed() };
    startup.StartupInfo.cb = size_of::<STARTUPINFOEXW>() as u32;
    startup.StartupInfo.dwFlags = STARTF_USESTDHANDLES;
    startup.StartupInfo.hStdInput = stdin_handle;
    startup.StartupInfo.hStdOutput = stdout_handle;
    startup.StartupInfo.hStdError = stderr_handle;
    startup.lpAttributeList = attributes.pointer;
    let mut process: PROCESS_INFORMATION = unsafe { zeroed() };
    let executable = wide(validated.executable.as_os_str());
    let mut command_line = wide_string(&build_command_line(
        &validated.executable,
        &validated.request.arguments,
    ));
    let working_directory = wide(validated.working_directory.as_os_str());
    let environment = build_environment_block(&validated.request, &validated.setup, token)?;
    let created = unsafe {
        CreateProcessAsUserW(
            token,
            executable.as_ptr(),
            command_line.as_mut_ptr(),
            null(),
            null(),
            1,
            CREATE_SUSPENDED
                | CREATE_UNICODE_ENVIRONMENT
                | CREATE_NO_WINDOW
                | EXTENDED_STARTUPINFO_PRESENT,
            environment.as_ptr() as *const c_void,
            working_directory.as_ptr(),
            &startup.StartupInfo,
            &mut process,
        )
    };
    if created == 0 {
        let code = unsafe { GetLastError() };
        let error = if code == ERROR_PRIVILEGE_NOT_HELD {
            SandboxFailure::setup(
                "Windows launcher lacks CreateProcessAsUser privileges; run elevated sandbox setup",
            )
        } else {
            SandboxFailure::new(
                ErrorCode::BackendUnavailable,
                format!("failed to launch the restricted Windows child (Win32 {code})"),
            )
        };
        return Err(error);
    }
    Ok(process)
}

fn create_job(request: &ExecutionRequest) -> Result<OwnedHandle, SandboxFailure> {
    let job = OwnedHandle::new(unsafe { CreateJobObjectW(null(), null()) })?;
    let mut limits: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = unsafe { zeroed() };
    limits.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        | JOB_OBJECT_LIMIT_ACTIVE_PROCESS
        | JOB_OBJECT_LIMIT_PROCESS_MEMORY
        | JOB_OBJECT_LIMIT_JOB_MEMORY
        | JOB_OBJECT_LIMIT_PROCESS_TIME;
    limits.BasicLimitInformation.ActiveProcessLimit = request.profile.limits.process_limit;
    limits.BasicLimitInformation.PerProcessUserTimeLimit =
        request.profile.limits.timeout_millis.saturating_mul(10_000) as i64;
    limits.ProcessMemoryLimit = request.profile.limits.memory_bytes as usize;
    limits.JobMemoryLimit = request.profile.limits.memory_bytes as usize;
    let applied = unsafe {
        SetInformationJobObject(
            job.raw(),
            JobObjectExtendedLimitInformation,
            &limits as *const _ as *const c_void,
            size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        )
    };
    if applied == 0 {
        return Err(last_error(
            ErrorCode::BackendUnavailable,
            "failed to apply Windows Job Object limits",
        ));
    }
    Ok(job)
}

fn create_restricted_token(
    token: HANDLE,
    account_sid: &[u8],
    writer_sid: Option<&[u8]>,
) -> Result<OwnedHandle, SandboxFailure> {
    let restricted_code_sid = parse_sid("S-1-5-12")?;
    let mut restrictions = vec![
        SID_AND_ATTRIBUTES {
            Sid: account_sid.as_ptr() as PSID,
            Attributes: 0,
        },
        SID_AND_ATTRIBUTES {
            Sid: restricted_code_sid.as_ptr() as PSID,
            Attributes: 0,
        },
    ];
    if let Some(sid) = writer_sid {
        restrictions.push(SID_AND_ATTRIBUTES {
            Sid: sid.as_ptr() as PSID,
            Attributes: 0,
        });
    }
    let mut restricted = null_mut();
    let created = unsafe {
        CreateRestrictedToken(
            token,
            DISABLE_MAX_PRIVILEGE | WRITE_RESTRICTED,
            0,
            null(),
            0,
            null(),
            restrictions.len() as u32,
            restrictions.as_ptr(),
            &mut restricted,
        )
    };
    if created == 0 {
        return Err(last_error(
            ErrorCode::SetupRequired,
            "failed to create the dedicated restricted token",
        ));
    }
    OwnedHandle::new(restricted)
}

fn logon_account(account: &str, password: &[u16]) -> Result<OwnedHandle, SandboxFailure> {
    let username = wide_string(account);
    let domain = wide_string(".");
    let mut password_z = password.to_vec();
    password_z.push(0);
    let mut token = null_mut();
    let logged_on = unsafe {
        LogonUserW(
            username.as_ptr(),
            domain.as_ptr(),
            password_z.as_ptr(),
            LOGON32_LOGON_BATCH,
            LOGON32_PROVIDER_DEFAULT,
            &mut token,
        )
    };
    password_z.fill(0);
    if logged_on == 0 {
        return Err(last_error(
            ErrorCode::SetupRequired,
            "dedicated Windows sandbox account cannot log on",
        ));
    }
    OwnedHandle::new(token)
}

fn current_process_token() -> Result<OwnedHandle, SandboxFailure> {
    process_token(unsafe { GetCurrentProcess() })
}

fn process_token(process: HANDLE) -> Result<OwnedHandle, SandboxFailure> {
    let mut token = null_mut();
    let opened = unsafe {
        OpenProcessToken(
            process,
            TOKEN_QUERY | TOKEN_DUPLICATE | TOKEN_ASSIGN_PRIMARY,
            &mut token,
        )
    };
    if opened == 0 {
        return Err(last_error(
            ErrorCode::BackendUnavailable,
            "Windows sandbox cannot inspect a process token",
        ));
    }
    OwnedHandle::new(token)
}

fn verify_runner_identity(token: HANDLE, expected_sid: &[u8]) -> Result<(), SandboxFailure> {
    verify_process_identity(
        token,
        expected_sid,
        "Windows sandbox runner is executing under an unexpected account",
    )
}

fn verify_process_identity(
    token: HANDLE,
    expected_sid: &[u8],
    mismatch_message: &str,
) -> Result<(), SandboxFailure> {
    let mut required = 0u32;
    unsafe {
        GetTokenInformation(token, TokenUser, null_mut(), 0, &mut required);
    }
    if required == 0 || unsafe { GetLastError() } != ERROR_INSUFFICIENT_BUFFER {
        return Err(SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            "Windows sandbox runner token is invalid",
        ));
    }
    let mut buffer = vec![0u8; required as usize];
    let loaded = unsafe {
        GetTokenInformation(
            token,
            TokenUser,
            buffer.as_mut_ptr() as *mut c_void,
            required,
            &mut required,
        )
    };
    if loaded == 0 {
        return Err(last_error(
            ErrorCode::BackendUnavailable,
            "Windows sandbox runner identity cannot be read",
        ));
    }
    let token_user = unsafe { &*(buffer.as_ptr() as *const TOKEN_USER) };
    if token_user.User.Sid.is_null() || unsafe { IsValidSid(token_user.User.Sid) } == 0 {
        return Err(SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            "Windows sandbox runner identity SID is invalid",
        ));
    }
    let sid_len = unsafe { GetLengthSid(token_user.User.Sid) } as usize;
    let actual_sid =
        unsafe { std::slice::from_raw_parts(token_user.User.Sid as *const u8, sid_len) };
    if actual_sid != expected_sid {
        return Err(SandboxFailure::new(
            ErrorCode::PolicyDenied,
            mismatch_message,
        ));
    }
    Ok(())
}

fn decrypt_credential(path: &Path) -> Result<SecretWide, SandboxFailure> {
    let mut encrypted = fs::read(path)
        .map_err(|_| SandboxFailure::setup("Windows sandbox credential is unavailable"))?;
    if encrypted.is_empty() || encrypted.len() > 64 * 1024 {
        return Err(SandboxFailure::setup(
            "Windows sandbox credential is invalid",
        ));
    }
    let input = DataBlob {
        size: encrypted.len() as u32,
        data: encrypted.as_mut_ptr(),
    };
    let mut entropy_bytes = ENTROPY.to_vec();
    let entropy = DataBlob {
        size: entropy_bytes.len() as u32,
        data: entropy_bytes.as_mut_ptr(),
    };
    let mut output = DataBlob {
        size: 0,
        data: null_mut(),
    };
    let decrypted = unsafe {
        CryptUnprotectData(
            &input,
            null_mut(),
            &entropy,
            null_mut(),
            null_mut(),
            1,
            &mut output,
        )
    };
    encrypted.fill(0);
    entropy_bytes.fill(0);
    if decrypted == 0 || output.data.is_null() || output.size == 0 || !output.size.is_multiple_of(2)
    {
        if !output.data.is_null() {
            zero_and_free_blob(&output);
        }
        return Err(SandboxFailure::setup(
            "Windows sandbox credential cannot be decrypted by this owner",
        ));
    }
    let password = unsafe {
        std::slice::from_raw_parts(output.data as *const u16, output.size as usize / 2).to_vec()
    };
    zero_and_free_blob(&output);
    Ok(SecretWide(password))
}

fn zero_and_free_blob(blob: &DataBlob) {
    for offset in 0..blob.size as usize {
        unsafe {
            std::ptr::write_volatile(blob.data.add(offset), 0);
        }
    }
    unsafe {
        LocalFree(blob.data as *mut c_void);
    }
}

fn lookup_account_sid(account: &str) -> Result<Vec<u8>, SandboxFailure> {
    let account = wide_string(account);
    let mut sid_size = 0u32;
    let mut domain_size = 0u32;
    let mut sid_type = 0i32;
    unsafe {
        LookupAccountNameW(
            null(),
            account.as_ptr(),
            null_mut(),
            &mut sid_size,
            null_mut(),
            &mut domain_size,
            &mut sid_type,
        );
    }
    if unsafe { GetLastError() } != ERROR_INSUFFICIENT_BUFFER || sid_size == 0 {
        return Err(SandboxFailure::setup(
            "dedicated Windows sandbox account is missing",
        ));
    }
    let mut sid = vec![0u8; sid_size as usize];
    let mut domain = vec![0u16; domain_size.max(1) as usize];
    let looked_up = unsafe {
        LookupAccountNameW(
            null(),
            account.as_ptr(),
            sid.as_mut_ptr() as PSID,
            &mut sid_size,
            domain.as_mut_ptr(),
            &mut domain_size,
            &mut sid_type,
        )
    };
    if looked_up == 0 || unsafe { IsValidSid(sid.as_ptr() as PSID) } == 0 {
        return Err(SandboxFailure::setup(
            "dedicated Windows sandbox account SID is invalid",
        ));
    }
    sid.truncate(unsafe { GetLengthSid(sid.as_ptr() as PSID) } as usize);
    Ok(sid)
}

fn ensure_app_container_profile(name: &str, expected_sid: &[u8]) -> Result<(), SandboxFailure> {
    let name = wide_string(name);
    let display_name = wide_string("Promethe Sandbox");
    let description = wide_string("Network-isolated Promethe process sandbox");
    let mut sid: PSID = null_mut();
    let mut result = unsafe {
        CreateAppContainerProfile(
            name.as_ptr(),
            display_name.as_ptr(),
            description.as_ptr(),
            null(),
            0,
            &mut sid,
        )
    };
    if result == HRESULT_ALREADY_EXISTS {
        result = unsafe { DeriveAppContainerSidFromAppContainerName(name.as_ptr(), &mut sid) };
    }
    if result < 0 || sid.is_null() || unsafe { IsValidSid(sid) } == 0 {
        if !sid.is_null() {
            unsafe {
                FreeSid(sid);
            }
        }
        return Err(SandboxFailure::setup(format!(
            "Windows sandbox AppContainer profile is unavailable (HRESULT 0x{:08x})",
            result as u32,
        )));
    }
    let sid_length = unsafe { GetLengthSid(sid) } as usize;
    let actual_sid = unsafe { std::slice::from_raw_parts(sid as *const u8, sid_length) };
    let matches = actual_sid == expected_sid;
    unsafe {
        FreeSid(sid);
    }
    if !matches {
        return Err(SandboxFailure::setup(
            "Windows sandbox AppContainer identity does not match elevated setup",
        ));
    }
    Ok(())
}

fn parse_sid(value: &str) -> Result<Vec<u8>, SandboxFailure> {
    if value.len() > 184 || !value.starts_with("S-") {
        return Err(SandboxFailure::setup(
            "Windows sandbox owner SID is invalid",
        ));
    }
    let value = wide_string(value);
    let mut sid: PSID = null_mut();
    if unsafe { ConvertStringSidToSidW(value.as_ptr(), &mut sid) } == 0 || sid.is_null() {
        return Err(last_error(
            ErrorCode::SetupRequired,
            "Windows sandbox owner SID is invalid",
        ));
    }
    let result = if unsafe { IsValidSid(sid) } == 0 {
        Err(SandboxFailure::setup(
            "Windows sandbox owner SID is invalid",
        ))
    } else {
        let length = unsafe { GetLengthSid(sid) } as usize;
        Ok(unsafe { std::slice::from_raw_parts(sid as *const u8, length) }.to_vec())
    };
    unsafe {
        LocalFree(sid);
    }
    result
}

fn sid_string(sid: &[u8]) -> Result<String, SandboxFailure> {
    if sid.len() < 8 {
        return Err(SandboxFailure::setup("Windows sandbox SID is truncated"));
    }
    let count = sid[1] as usize;
    if sid.len() < 8 + count * 4 {
        return Err(SandboxFailure::setup("Windows sandbox SID is malformed"));
    }
    let authority = sid[2..8]
        .iter()
        .fold(0u64, |value, byte| (value << 8) | u64::from(*byte));
    let mut result = format!("S-{}-{authority}", sid[0]);
    for index in 0..count {
        let offset = 8 + index * 4;
        let sub = u32::from_le_bytes(sid[offset..offset + 4].try_into().unwrap());
        result.push_str(&format!("-{sub}"));
    }
    Ok(result)
}

fn verify_effective_rights(path: &Path, sid: &[u8], required: u32) -> Result<(), SandboxFailure> {
    let rights = effective_rights(path, sid)?;
    if rights & required != required {
        return Err(SandboxFailure::setup(
            format!(
                "sandbox ACL is missing required access on {} (required 0x{required:08x}, effective 0x{rights:08x})",
                path.display(),
            ),
        ));
    }
    Ok(())
}

fn verify_protected_workspace_paths(
    workspace: &Path,
    offline_sid: &[u8],
    online_sid: &[u8],
    writer_sid: &[u8],
    app_container_sid: &[u8],
) -> Result<(), SandboxFailure> {
    for name in [".git", ".promethe", ".codex", ".agents"] {
        let protected = workspace.join(name);
        if !protected.exists() {
            return Err(SandboxFailure::setup(
                "a protected workspace path is missing; repair setup",
            ));
        }
        for sid in [offline_sid, online_sid, writer_sid, app_container_sid] {
            verify_effective_rights_absent(
                &protected,
                sid,
                CONTROL_MUTATION_RIGHTS | FILE_DELETE_CHILD,
            )?;
        }
    }
    Ok(())
}

fn verify_effective_rights_absent(
    path: &Path,
    sid: &[u8],
    forbidden: u32,
) -> Result<(), SandboxFailure> {
    let rights = effective_rights(path, sid)?;
    if rights & forbidden != 0 {
        return Err(SandboxFailure::setup(
            format!(
                "sandbox ACL grants forbidden access on {} (forbidden 0x{:08x}, effective 0x{rights:08x}); repair setup",
                path.display(),
                rights & forbidden,
            ),
        ));
    }
    Ok(())
}

fn effective_rights(path: &Path, sid: &[u8]) -> Result<u32, SandboxFailure> {
    let path = wide(path.as_os_str());
    let mut dacl = null_mut();
    let mut descriptor = null_mut();
    let result = unsafe {
        GetNamedSecurityInfoW(
            path.as_ptr(),
            SE_FILE_OBJECT,
            DACL_SECURITY_INFORMATION,
            null_mut(),
            null_mut(),
            &mut dacl,
            null_mut(),
            &mut descriptor,
        )
    };
    if result != 0 || dacl.is_null() || descriptor.is_null() {
        return Err(SandboxFailure::setup("workspace ACL cannot be inspected"));
    }
    let mut trustee = TrusteeW {
        multiple_trustee: null_mut(),
        multiple_trustee_operation: 0,
        trustee_form: TRUSTEE_IS_SID,
        trustee_type: TRUSTEE_IS_UNKNOWN,
        name: sid.as_ptr() as *mut u16,
    };
    let mut rights = 0u32;
    let rights_result = unsafe { GetEffectiveRightsFromAclW(dacl, &mut trustee, &mut rights) };
    unsafe {
        LocalFree(descriptor);
    }
    if rights_result != 0 {
        return Err(SandboxFailure::setup("filesystem ACL cannot be evaluated"));
    }
    Ok(rights)
}

fn setup_root() -> Result<PathBuf, SandboxFailure> {
    #[cfg(test)]
    if let Some(root) = TEST_SETUP_ROOT.with(|value| value.borrow().clone()) {
        return fs::canonicalize(root)
            .map_err(|_| SandboxFailure::setup("elevated Windows sandbox setup is missing"));
    }
    let program_data = std::env::var_os("ProgramData")
        .ok_or_else(|| SandboxFailure::setup("ProgramData is unavailable"))?;
    let root = PathBuf::from(program_data).join("Promethe").join("sandbox");
    fs::canonicalize(&root)
        .map_err(|_| SandboxFailure::setup("elevated Windows sandbox setup is missing"))
}

fn safe_setup_child(root: &Path, value: &str) -> Result<PathBuf, SandboxFailure> {
    if value.is_empty()
        || value.contains('\0')
        || Path::new(value).is_absolute()
        || Path::new(value)
            .components()
            .any(|part| !matches!(part, std::path::Component::Normal(_)))
    {
        return Err(SandboxFailure::setup(
            "Windows sandbox setup contains an unsafe path",
        ));
    }
    let path = root.join(value);
    if path.exists() {
        match fs::canonicalize(&path) {
            Ok(canonical) => {
                if !canonical.starts_with(root) {
                    return Err(SandboxFailure::setup(
                        "Windows sandbox setup path escapes ProgramData",
                    ));
                }
                Ok(canonical)
            }
            Err(error) if error.kind() == std::io::ErrorKind::PermissionDenied => {
                let parent = path.parent().ok_or_else(|| {
                    SandboxFailure::setup("Windows sandbox setup path has no parent")
                })?;
                let canonical_parent = fs::canonicalize(parent).map_err(|_| {
                    SandboxFailure::setup("Windows sandbox setup parent cannot be resolved")
                })?;
                if !canonical_parent.starts_with(root) {
                    return Err(SandboxFailure::setup(
                        "Windows sandbox setup path escapes ProgramData",
                    ));
                }
                let file_name = path.file_name().ok_or_else(|| {
                    SandboxFailure::setup("Windows sandbox setup path has no file name")
                })?;
                Ok(canonical_parent.join(file_name))
            }
            Err(_) => Err(SandboxFailure::setup(
                "Windows sandbox setup path cannot be resolved",
            )),
        }
    } else {
        Ok(path)
    }
}

fn canonical_roots(values: &[String]) -> Result<Vec<PathBuf>, SandboxFailure> {
    let mut roots = Vec::new();
    for value in values {
        validate_text("workspace root", value, 4_096)?;
        let root = canonical_directory(Path::new(value), "workspace root")?;
        if root.parent().is_none() {
            return Err(SandboxFailure::new(
                ErrorCode::WorkspaceViolation,
                "filesystem roots cannot be workspaces",
            ));
        }
        if !roots.contains(&root) {
            roots.push(root);
        }
    }
    Ok(roots)
}

fn canonical_directory(path: &Path, label: &str) -> Result<PathBuf, SandboxFailure> {
    if !path.is_absolute() {
        return Err(SandboxFailure::new(
            ErrorCode::WorkspaceViolation,
            format!("{label} must be absolute"),
        ));
    }
    let canonical = fs::canonicalize(path).map_err(|_| {
        SandboxFailure::new(
            ErrorCode::WorkspaceViolation,
            format!("{label} does not exist or cannot be resolved"),
        )
    })?;
    if !canonical.is_dir() {
        return Err(SandboxFailure::new(
            ErrorCode::WorkspaceViolation,
            format!("{label} must be a directory"),
        ));
    }
    Ok(canonical)
}

fn resolve_executable(value: &str) -> Result<PathBuf, SandboxFailure> {
    let path = Path::new(value);
    if path.is_absolute() {
        let resolved = fs::canonicalize(path).map_err(|_| {
            SandboxFailure::new(ErrorCode::InvalidRequest, "executable does not exist")
        })?;
        if resolved.is_file() && executable_extension_allowed(&resolved) {
            return Ok(resolved);
        }
        return Err(SandboxFailure::new(
            ErrorCode::PolicyDenied,
            "executable must be a native Windows executable",
        ));
    }
    if path.components().count() != 1 {
        return Err(SandboxFailure::new(
            ErrorCode::InvalidRequest,
            "executable must be a bare name or an absolute path",
        ));
    }
    let path_env = std::env::var_os("PATH").unwrap_or_default();
    let candidates = if path.extension().is_some() {
        vec![value.to_string()]
    } else {
        vec![format!("{value}.exe"), format!("{value}.com")]
    };
    for directory in std::env::split_paths(&path_env) {
        for candidate in &candidates {
            let candidate = directory.join(candidate);
            if candidate.is_file() {
                let resolved = fs::canonicalize(candidate).map_err(|_| {
                    SandboxFailure::new(ErrorCode::InvalidRequest, "executable cannot be resolved")
                })?;
                if executable_extension_allowed(&resolved) {
                    return Ok(resolved);
                }
            }
        }
    }
    Err(SandboxFailure::new(
        ErrorCode::InvalidRequest,
        "executable was not found on the trusted PATH",
    ))
}

fn executable_extension_allowed(path: &Path) -> bool {
    path.extension()
        .and_then(|value| value.to_str())
        .map(|value| value.eq_ignore_ascii_case("exe") || value.eq_ignore_ascii_case("com"))
        .unwrap_or(false)
}

fn create_output_pipe() -> Result<(OwnedHandle, OwnedHandle), SandboxFailure> {
    let attributes = SECURITY_ATTRIBUTES {
        nLength: size_of::<SECURITY_ATTRIBUTES>() as u32,
        lpSecurityDescriptor: null_mut(),
        bInheritHandle: 1,
    };
    let mut read_handle = null_mut();
    let mut write_handle = null_mut();
    if unsafe {
        CreatePipe(
            &mut read_handle,
            &mut write_handle,
            &attributes,
            PIPE_BUFFER_BYTES,
        )
    } == 0
    {
        return Err(last_error(
            ErrorCode::InternalError,
            "failed to create a private sandbox output pipe",
        ));
    }
    let read_handle = OwnedHandle::new(read_handle)?;
    let write_handle = OwnedHandle::new(write_handle)?;
    if unsafe { SetHandleInformation(read_handle.raw(), HANDLE_FLAG_INHERIT, 0) } == 0 {
        return Err(last_error(
            ErrorCode::InternalError,
            "failed to protect a sandbox output pipe",
        ));
    }
    Ok((read_handle, write_handle))
}

fn spawn_output_reader(
    read_handle: OwnedHandle,
    retain_limit: usize,
    hard_limit: usize,
    limit_hit: Arc<AtomicBool>,
) -> JoinHandle<CapturedOutput> {
    let raw_handle = read_handle.into_raw() as isize;
    std::thread::spawn(move || {
        let handle = OwnedHandle(raw_handle as HANDLE);
        let mut retained = Vec::with_capacity(retain_limit.min(8_192));
        let mut total = 0usize;
        let mut buffer = [0u8; 8_192];
        loop {
            let mut read = 0u32;
            let success = unsafe {
                ReadFile(
                    handle.raw(),
                    buffer.as_mut_ptr(),
                    buffer.len() as u32,
                    &mut read,
                    null_mut(),
                )
            };
            if success == 0 || read == 0 {
                break;
            }
            let read = read as usize;
            total = total.saturating_add(read);
            if retained.len() < retain_limit {
                let keep = (retain_limit - retained.len()).min(read);
                retained.extend_from_slice(&buffer[..keep]);
            }
            if total > hard_limit {
                limit_hit.store(true, Ordering::Release);
            }
        }
        CapturedOutput {
            text: String::from_utf8_lossy(&retained).into_owned(),
            truncated: total > retain_limit,
        }
    })
}

fn set_inheritable(file: &File) -> Result<(), SandboxFailure> {
    let result = unsafe {
        SetHandleInformation(
            file.as_raw_handle() as HANDLE,
            HANDLE_FLAG_INHERIT,
            HANDLE_FLAG_INHERIT,
        )
    };
    if result == 0 {
        return Err(last_error(
            ErrorCode::InternalError,
            "failed to prepare a child standard handle",
        ));
    }
    Ok(())
}

fn build_environment_block(
    request: &ExecutionRequest,
    setup: &VerifiedSetup,
    token: HANDLE,
) -> Result<Vec<u16>, SandboxFailure> {
    let mut environment = std::collections::BTreeMap::new();
    let system_root = std::env::var("SystemRoot")
        .map_err(|_| SandboxFailure::setup("SystemRoot is unavailable"))?;
    let temp = safe_setup_child(&setup.root, &setup.manifest.temp_directory)?;
    environment.insert("PATH".to_string(), format!("{system_root}\\System32"));
    environment.insert("SystemRoot".to_string(), system_root.clone());
    environment.insert("WINDIR".to_string(), system_root);
    environment.insert("TEMP".to_string(), temp.to_string_lossy().into_owned());
    environment.insert("TMP".to_string(), temp.to_string_lossy().into_owned());
    for (key, value) in sandbox_account_environment(token)? {
        environment.insert(key, value);
    }
    for (key, value) in &request.environment {
        environment.insert(key.clone(), value.clone());
    }
    let mut block = Vec::new();
    for (key, value) in environment {
        block.extend(format!("{key}={value}").encode_utf16());
        block.push(0);
    }
    block.push(0);
    Ok(block)
}

fn sandbox_account_environment(
    token: HANDLE,
) -> Result<std::collections::BTreeMap<String, String>, SandboxFailure> {
    let mut raw_environment: *mut c_void = null_mut();
    if unsafe { CreateEnvironmentBlock(&mut raw_environment, token, 0) } == 0
        || raw_environment.is_null()
    {
        return Err(last_error(
            ErrorCode::BackendUnavailable,
            "Windows sandbox account environment is unavailable",
        ));
    }
    let mut result = std::collections::BTreeMap::new();
    let mut cursor = raw_environment as *const u16;
    loop {
        let mut length = 0usize;
        while unsafe { *cursor.add(length) } != 0 {
            length += 1;
            if length > 32_767 {
                unsafe {
                    DestroyEnvironmentBlock(raw_environment);
                }
                return Err(SandboxFailure::setup(
                    "Windows sandbox account environment is invalid",
                ));
            }
        }
        if length == 0 {
            break;
        }
        let entry = String::from_utf16_lossy(unsafe { std::slice::from_raw_parts(cursor, length) });
        cursor = unsafe { cursor.add(length + 1) };
        let Some((key, value)) = entry.split_once('=') else {
            continue;
        };
        if matches!(
            key.to_ascii_uppercase().as_str(),
            "ALLUSERSPROFILE"
                | "APPDATA"
                | "COMMONPROGRAMFILES"
                | "COMMONPROGRAMFILES(X86)"
                | "COMMONPROGRAMW6432"
                | "HOMEDRIVE"
                | "HOMEPATH"
                | "LOCALAPPDATA"
                | "PROGRAMDATA"
                | "PROGRAMFILES"
                | "PROGRAMFILES(X86)"
                | "PROGRAMW6432"
                | "USERPROFILE"
        ) {
            result.insert(key.to_string(), value.to_string());
        }
    }
    unsafe {
        DestroyEnvironmentBlock(raw_environment);
    }
    Ok(result)
}

fn build_command_line(executable: &Path, arguments: &[String]) -> String {
    let mut parts = Vec::with_capacity(arguments.len() + 1);
    parts.push(quote_windows_argument(&executable.to_string_lossy()));
    parts.extend(arguments.iter().map(|value| quote_windows_argument(value)));
    parts.join(" ")
}

fn quote_windows_argument(value: &str) -> String {
    if !value.is_empty()
        && !value
            .chars()
            .any(|character| character.is_whitespace() || character == '"')
    {
        return value.to_string();
    }
    let mut quoted = String::from("\"");
    let mut slashes = 0usize;
    for character in value.chars() {
        if character == '\\' {
            slashes += 1;
        } else if character == '"' {
            quoted.push_str(&"\\".repeat(slashes * 2 + 1));
            quoted.push('"');
            slashes = 0;
        } else {
            quoted.push_str(&"\\".repeat(slashes));
            slashes = 0;
            quoted.push(character);
        }
    }
    quoted.push_str(&"\\".repeat(slashes * 2));
    quoted.push('"');
    quoted
}

fn active_jobs() -> &'static Mutex<HashMap<String, isize>> {
    ACTIVE_JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn cancelled() -> &'static Mutex<HashSet<String>> {
    CANCELLED.get_or_init(|| Mutex::new(HashSet::new()))
}

fn cancellation_requested(execution_id: &str) -> bool {
    cancelled()
        .lock()
        .map(|values| values.contains(execution_id))
        .unwrap_or(true)
}

fn remove_cancellation(execution_id: &str) -> bool {
    cancelled()
        .lock()
        .map(|mut values| values.remove(execution_id))
        .unwrap_or(true)
}

fn cancelled_execution(execution_id: &str) -> ExecutionResult {
    ExecutionResult {
        protocol_version: PROTOCOL_VERSION,
        execution_id: execution_id.to_owned(),
        exit_code: None,
        stdout: String::new(),
        stderr: String::new(),
        timed_out: false,
        cancelled: true,
        truncated: false,
        duration_millis: 0,
        error_code: Some(ErrorCode::Cancelled),
        error_message: Some("sandboxed execution was cancelled".into()),
    }
}

fn validate_identifier(label: &str, value: &str) -> Result<(), SandboxFailure> {
    if !valid_identifier(value) {
        return Err(SandboxFailure::new(
            ErrorCode::InvalidRequest,
            format!("{label} must contain 1-128 safe ASCII characters"),
        ));
    }
    Ok(())
}

fn valid_identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'-' | b'_' | b'.'))
}

fn validate_manifest_name(value: &str) -> Result<(), SandboxFailure> {
    if !valid_identifier(value) || value.len() > 20 {
        return Err(SandboxFailure::setup(
            "Windows sandbox setup contains an invalid account name",
        ));
    }
    Ok(())
}

fn validate_text(label: &str, value: &str, max: usize) -> Result<(), SandboxFailure> {
    if value.is_empty() || value.len() > max || value.contains('\0') {
        return Err(SandboxFailure::new(
            ErrorCode::InvalidRequest,
            format!("{label} is empty, too long, or contains NUL"),
        ));
    }
    Ok(())
}

fn allowed_environment_key(key: &str) -> bool {
    matches!(key, "LANG" | "LC_ALL" | "LC_CTYPE" | "TERM" | "TZ")
}

fn looks_sensitive(key: &str) -> bool {
    let normalized = key.to_ascii_uppercase();
    ["TOKEN", "SECRET", "PASSWORD", "API_KEY", "CREDENTIAL"]
        .iter()
        .any(|needle| normalized.contains(needle))
}

fn is_within_any(path: &Path, roots: &[PathBuf]) -> bool {
    roots.iter().any(|root| path.starts_with(root))
}

fn wide(value: &std::ffi::OsStr) -> Vec<u16> {
    value.encode_wide().chain(Some(0)).collect()
}

fn wide_string(value: &str) -> Vec<u16> {
    value.encode_utf16().chain(Some(0)).collect()
}

fn last_error(code: ErrorCode, message: &str) -> SandboxFailure {
    let windows_code = unsafe { GetLastError() };
    SandboxFailure::new(code, format!("{message} (Win32 {windows_code})"))
}

fn error_response(operation: IpcOperation, error: SandboxFailure) -> IpcResponse {
    IpcResponse {
        protocol_version: PROTOCOL_VERSION,
        operation,
        execution: None,
        status: None,
        error_code: Some(error.code),
        error_message: Some(error.message),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    struct MissingSetupGuard;

    impl MissingSetupGuard {
        fn install() -> Self {
            let missing = std::env::temp_dir().join(format!(
                "promethe-missing-windows-setup-{}",
                uuid::Uuid::new_v4().simple(),
            ));
            TEST_SETUP_ROOT.with(|value| value.replace(Some(missing)));
            Self
        }
    }

    impl Drop for MissingSetupGuard {
        fn drop(&mut self) {
            TEST_SETUP_ROOT.with(|value| value.replace(None));
        }
    }
    use crate::protocol::{ApprovalPolicy, PermissionProfile, ResourceLimits};
    use std::collections::{BTreeMap, BTreeSet};

    #[test]
    fn quotes_windows_arguments() {
        assert_eq!(quote_windows_argument("plain"), "plain");
        assert_eq!(quote_windows_argument(""), "\"\"");
        assert_eq!(quote_windows_argument("a b"), "\"a b\"");
        assert_eq!(quote_windows_argument("a\\\"b"), "\"a\\\\\\\"b\"");
    }

    #[test]
    fn identifiers_are_path_safe() {
        assert!(valid_identifier("exec-123_test"));
        assert!(!valid_identifier("../escape"));
        assert!(!valid_identifier("with space"));
    }

    #[test]
    fn manifest_names_honor_windows_account_limit() {
        assert!(validate_manifest_name("PrometheSbxOffline").is_ok());
        assert!(validate_manifest_name("12345678901234567890").is_ok());
        assert!(validate_manifest_name("123456789012345678901").is_err());
    }

    #[test]
    fn read_access_is_not_classified_as_mutation() {
        assert_eq!(FILE_GENERIC_READ & CONTROL_MUTATION_RIGHTS, 0);
        assert_eq!(
            FILE_WRITE_RIGHTS & CONTROL_MUTATION_RIGHTS,
            FILE_WRITE_RIGHTS
        );
        assert_eq!(FILE_READ_DATA & CONTROL_MUTATION_RIGHTS, 0);
    }

    #[test]
    fn environment_allow_list_excludes_secrets_and_loader_controls() {
        assert!(allowed_environment_key("LANG"));
        assert!(!allowed_environment_key("PATH"));
        assert!(looks_sensitive("OPENAI_API_KEY"));
        assert!(looks_sensitive("session_token"));
    }

    #[test]
    fn windows_execution_requires_elevated_setup() {
        let _missing_setup = MissingSetupGuard::install();
        let workspace = std::env::temp_dir().to_string_lossy().into_owned();
        let executable = PathBuf::from(std::env::var("SystemRoot").unwrap())
            .join("System32")
            .join("whoami.exe")
            .to_string_lossy()
            .into_owned();
        let response = execute(IpcRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: IpcOperation::Execute,
            execution: Some(ExecutionRequest {
                protocol_version: PROTOCOL_VERSION,
                execution_id: "exec-1".into(),
                session_id: "session-1".into(),
                executable,
                arguments: Vec::new(),
                working_directory: workspace.clone(),
                environment: BTreeMap::new(),
                sensitive_environment_keys: BTreeSet::new(),
                profile: PermissionProfile {
                    mode: SandboxMode::WorkspaceWrite,
                    approval_policy: ApprovalPolicy::OnRequest,
                    network_mode: NetworkMode::Off,
                    readable_roots: vec![workspace.clone()],
                    writable_roots: vec![workspace],
                    protected_paths: Vec::new(),
                    allowed_domains: Vec::new(),
                    limits: ResourceLimits {
                        timeout_millis: 1_000,
                        max_output_bytes_per_stream: 1_024,
                        memory_bytes: 64 * 1024 * 1024,
                        cpu_limit: 1.0,
                        process_limit: 8,
                    },
                },
                interactive: false,
            }),
            execution_id: None,
        });

        let result = response.execution.expect("execute must return a result");
        assert_eq!(result.execution_id, "exec-1");
        assert!(matches!(result.error_code, Some(ErrorCode::SetupRequired)));
        assert!(result
            .error_message
            .as_deref()
            .is_some_and(|message| message.contains("setup is missing")));
    }

    #[test]
    fn windows_status_and_self_test_request_setup() {
        let _missing_setup = MissingSetupGuard::install();
        let status_response = status();
        let status = status_response.status.expect("status payload");
        assert!(!status.available);
        assert!(matches!(status.backend, SandboxBackend::WindowsElevated));
        assert!(status.setup_required);
        assert!(status
            .message
            .as_deref()
            .is_some_and(|message| message.contains("setup is missing")));

        let self_test_response = self_test();
        let self_test_status = self_test_response.status.expect("self-test status payload");
        assert!(!self_test_status.self_test_passed);
        assert!(matches!(
            self_test_response.error_code,
            Some(ErrorCode::SetupRequired)
        ));
    }
}
