use std::collections::{HashMap, HashSet};
use std::ffi::c_void;
use std::fs::{self, File, OpenOptions};
use std::io::Read;
use std::mem::{size_of, zeroed};
use std::os::windows::ffi::OsStrExt;
use std::os::windows::io::AsRawHandle;
use std::path::{Path, PathBuf};
use std::ptr::{null, null_mut};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use serde::Deserialize;
use windows_sys::Win32::Foundation::{
    CloseHandle, GetLastError, SetHandleInformation, HANDLE, HANDLE_FLAG_INHERIT, WAIT_OBJECT_0,
    WAIT_TIMEOUT,
};
use windows_sys::Win32::Security::{
    CreateRestrictedToken, GetLengthSid, IsValidSid, LogonUserW, LookupAccountNameW,
    DISABLE_MAX_PRIVILEGE, LOGON32_LOGON_BATCH, LOGON32_PROVIDER_DEFAULT, PSID, SID_AND_ATTRIBUTES,
    WRITE_RESTRICTED,
};
use windows_sys::Win32::System::JobObjects::{
    AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
    SetInformationJobObject, TerminateJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
    JOB_OBJECT_LIMIT_ACTIVE_PROCESS, JOB_OBJECT_LIMIT_JOB_MEMORY,
    JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE, JOB_OBJECT_LIMIT_PROCESS_MEMORY,
    JOB_OBJECT_LIMIT_PROCESS_TIME,
};
use windows_sys::Win32::System::Threading::{
    CreateProcessAsUserW, GetExitCodeProcess, ResumeThread, WaitForSingleObject, CREATE_NO_WINDOW,
    CREATE_SUSPENDED, CREATE_UNICODE_ENVIRONMENT, PROCESS_INFORMATION, STARTF_USESTDHANDLES,
    STARTUPINFOW,
};

use crate::protocol::{
    ErrorCode, ExecutionRequest, ExecutionResult, IpcOperation, IpcRequest, IpcResponse,
    NetworkMode, SandboxBackend, SandboxMode, Status, PROTOCOL_VERSION,
};

const SETUP_VERSION: u32 = 1;
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
const FILE_GENERIC_READ: u32 = 0x0012_0089;
const FILE_GENERIC_WRITE: u32 = 0x0012_0116;
const DACL_SECURITY_INFORMATION: u32 = 0x0000_0004;
const SE_FILE_OBJECT: u32 = 1;
const TRUSTEE_IS_SID: u32 = 0;
const TRUSTEE_IS_UNKNOWN: u32 = 0;
const READ_CONFINEMENT_UNCERTIFIED: &str = "Windows native sandbox execution is disabled: \
read confinement outside registered workspaces is not yet certified; use the Docker or WSL2 \
backend";

static ACTIVE_JOBS: OnceLock<Mutex<HashMap<String, isize>>> = OnceLock::new();
static CANCELLED: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();

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
    offline_account: String,
    offline_sid: String,
    online_account: String,
    online_sid: String,
    writer_group: String,
    writer_group_sid: String,
    workspace_roots: Vec<String>,
    offline_credential_file: String,
    online_credential_file: String,
    temp_directory: String,
}

struct VerifiedSetup {
    root: PathBuf,
    manifest: SetupManifest,
    offline_sid: Vec<u8>,
    online_sid: Vec<u8>,
    writer_sid: Vec<u8>,
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
    IpcResponse::execution_error(
        execution_id,
        ErrorCode::BackendUnavailable,
        READ_CONFINEMENT_UNCERTIFIED.into(),
    )
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
    let job = active_jobs()
        .lock()
        .ok()
        .and_then(|jobs| jobs.get(&execution_id).copied());
    let Some(job) = job else {
        return error_response(
            IpcOperation::Cancel,
            SandboxFailure::new(ErrorCode::InvalidRequest, "execution is not active"),
        );
    };
    if let Ok(mut cancelled) = cancelled().lock() {
        cancelled.insert(execution_id);
    }
    unsafe {
        TerminateJobObject(job as HANDLE, 1);
    }
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
    IpcResponse {
        protocol_version: PROTOCOL_VERSION,
        operation: IpcOperation::Status,
        execution: None,
        status: Some(Status {
            protocol_version: PROTOCOL_VERSION,
            available: false,
            backend: SandboxBackend::Unavailable,
            mode: SandboxMode::WorkspaceWrite,
            network_mode: NetworkMode::Off,
            degraded: false,
            setup_required: false,
            self_test_passed: false,
            message: Some(READ_CONFINEMENT_UNCERTIFIED.into()),
        }),
        error_code: None,
        error_message: None,
    }
}

pub fn self_test() -> IpcResponse {
    IpcResponse {
        protocol_version: PROTOCOL_VERSION,
        operation: IpcOperation::SelfTest,
        execution: None,
        status: Some(Status {
            protocol_version: PROTOCOL_VERSION,
            available: false,
            backend: SandboxBackend::Unavailable,
            mode: SandboxMode::WorkspaceWrite,
            network_mode: NetworkMode::Off,
            degraded: false,
            setup_required: false,
            self_test_passed: false,
            message: Some(READ_CONFINEMENT_UNCERTIFIED.into()),
        }),
        error_code: Some(ErrorCode::BackendUnavailable),
        error_message: Some(READ_CONFINEMENT_UNCERTIFIED.into()),
    }
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
    validate_manifest_name(&manifest.offline_account)?;
    validate_manifest_name(&manifest.online_account)?;
    validate_manifest_name(&manifest.writer_group)?;

    let offline_sid = lookup_account_sid(&manifest.offline_account)?;
    let online_sid = lookup_account_sid(&manifest.online_account)?;
    let writer_sid = lookup_account_sid(&manifest.writer_group)?;
    if sid_string(&offline_sid)? != manifest.offline_sid
        || sid_string(&online_sid)? != manifest.online_sid
        || sid_string(&writer_sid)? != manifest.writer_group_sid
    {
        return Err(SandboxFailure::setup(
            "Windows sandbox account identity does not match the elevated setup",
        ));
    }

    let workspace_roots = canonical_roots(&manifest.workspace_roots)?;
    if workspace_roots.is_empty() {
        return Err(SandboxFailure::setup(
            "Windows sandbox has no registered workspace",
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
            verify_effective_rights(root, &writer_sid, FILE_GENERIC_WRITE)?;
        }
    } else {
        for workspace in &workspace_roots {
            verify_effective_rights(workspace, &offline_sid, FILE_GENERIC_READ)?;
            verify_effective_rights(workspace, &writer_sid, FILE_GENERIC_WRITE)?;
        }
    }

    let offline_credential = safe_setup_child(&root, &manifest.offline_credential_file)?;
    let online_credential = safe_setup_child(&root, &manifest.online_credential_file)?;
    let temp_directory = safe_setup_child(&root, &manifest.temp_directory)?;
    if !offline_credential.is_file() || !online_credential.is_file() || !temp_directory.is_dir() {
        return Err(SandboxFailure::setup(
            "Windows sandbox credentials or temporary directory are missing",
        ));
    }

    Ok(VerifiedSetup {
        root,
        manifest,
        offline_sid,
        online_sid,
        writer_sid,
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
        let mut password = decrypt_credential(&credential_path)?;
        let token = logon_account(account, &password)?;
        password.fill(0);
        let _restricted = create_restricted_token(token.raw(), sid, Some(&setup.writer_sid))?;
    }
    Ok(())
}

fn run_execution(validated: ValidatedExecution) -> Result<ExecutionResult, SandboxFailure> {
    let execution_id = validated.request.execution_id.clone();
    {
        let jobs = active_jobs().lock().map_err(|_| {
            SandboxFailure::new(
                ErrorCode::InternalError,
                "execution registry is unavailable",
            )
        })?;
        if jobs.contains_key(&execution_id) {
            return Err(SandboxFailure::new(
                ErrorCode::InvalidRequest,
                "executionId is already active",
            ));
        }
    }

    let credential_path = safe_setup_child(
        &validated.setup.root,
        &validated.setup.manifest.offline_credential_file,
    )?;
    let mut password = decrypt_credential(&credential_path)?;
    let account = validated.setup.manifest.offline_account.clone();
    let logon_token = logon_account(&account, &password)?;
    password.fill(0);
    let restricted_token = create_restricted_token(
        logon_token.raw(),
        &validated.setup.offline_sid,
        if matches!(&validated.request.profile.mode, SandboxMode::WorkspaceWrite) {
            Some(&validated.setup.writer_sid)
        } else {
            None
        },
    )?;

    let temp_root = safe_setup_child(
        &validated.setup.root,
        &validated.setup.manifest.temp_directory,
    )?;
    let unique = uuid::Uuid::new_v4().simple().to_string();
    let stdout_path = temp_root.join(format!("{unique}.stdout"));
    let stderr_path = temp_root.join(format!("{unique}.stderr"));
    let stdout_file = create_inheritable_output(&stdout_path)?;
    let stderr_file = create_inheritable_output(&stderr_path)?;
    let stdin_file = OpenOptions::new().read(true).open("NUL").map_err(|error| {
        SandboxFailure::new(
            ErrorCode::InternalError,
            format!("failed to open null input: {error}"),
        )
    })?;
    set_inheritable(&stdin_file)?;

    let job = create_job(&validated.request)?;
    let started = Instant::now();
    let mut process = spawn_suspended(
        &validated,
        restricted_token.raw(),
        &stdin_file,
        &stdout_file,
        &stderr_file,
    )?;
    if unsafe { AssignProcessToJobObject(job.raw(), process.hProcess) } == 0 {
        unsafe {
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

    active_jobs()
        .lock()
        .map_err(|_| {
            SandboxFailure::new(
                ErrorCode::InternalError,
                "execution registry is unavailable",
            )
        })?
        .insert(execution_id.clone(), job.raw() as isize);

    let mut timed_out = false;
    let mut output_limit_hit = false;
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
        let hard_output_limit = validated
            .request
            .profile
            .limits
            .max_output_bytes_per_stream
            .saturating_mul(2) as u64;
        if file_len(&stdout_path) > hard_output_limit || file_len(&stderr_path) > hard_output_limit
        {
            output_limit_hit = true;
            unsafe {
                TerminateJobObject(job.raw(), 1);
            }
            break;
        }
    }
    unsafe {
        WaitForSingleObject(process.hProcess, 5_000);
    }

    let mut exit_code = 1u32;
    let got_exit = unsafe { GetExitCodeProcess(process.hProcess, &mut exit_code) } != 0;
    unsafe {
        CloseHandle(process.hProcess);
    }
    active_jobs()
        .lock()
        .ok()
        .map(|mut jobs| jobs.remove(&execution_id));
    let was_cancelled = cancelled()
        .lock()
        .ok()
        .map(|mut values| values.remove(&execution_id))
        .unwrap_or(false);

    drop(stdout_file);
    drop(stderr_file);
    let (stdout, stdout_truncated) = read_bounded(
        &stdout_path,
        validated.request.profile.limits.max_output_bytes_per_stream,
    );
    let (stderr, stderr_truncated) = read_bounded(
        &stderr_path,
        validated.request.profile.limits.max_output_bytes_per_stream,
    );
    let _ = fs::remove_file(&stdout_path);
    let _ = fs::remove_file(&stderr_path);

    Ok(ExecutionResult {
        protocol_version: PROTOCOL_VERSION,
        execution_id,
        exit_code: got_exit.then_some(exit_code as i32),
        stdout,
        stderr,
        timed_out,
        cancelled: was_cancelled,
        truncated: output_limit_hit || stdout_truncated || stderr_truncated,
        duration_millis: started.elapsed().as_millis() as u64,
        error_code: if timed_out {
            Some(ErrorCode::TimedOut)
        } else if was_cancelled {
            Some(ErrorCode::Cancelled)
        } else if output_limit_hit {
            Some(ErrorCode::ResourceLimit)
        } else {
            None
        },
        error_message: if timed_out {
            Some("sandboxed execution timed out".into())
        } else if was_cancelled {
            Some("sandboxed execution was cancelled".into())
        } else if output_limit_hit {
            Some("sandboxed execution exceeded the output limit".into())
        } else {
            None
        },
    })
}

fn spawn_suspended(
    validated: &ValidatedExecution,
    token: HANDLE,
    stdin_file: &File,
    stdout_file: &File,
    stderr_file: &File,
) -> Result<PROCESS_INFORMATION, SandboxFailure> {
    let mut startup: STARTUPINFOW = unsafe { zeroed() };
    startup.cb = size_of::<STARTUPINFOW>() as u32;
    startup.dwFlags = STARTF_USESTDHANDLES;
    startup.hStdInput = stdin_file.as_raw_handle() as HANDLE;
    startup.hStdOutput = stdout_file.as_raw_handle() as HANDLE;
    startup.hStdError = stderr_file.as_raw_handle() as HANDLE;
    let mut process: PROCESS_INFORMATION = unsafe { zeroed() };
    let executable = wide(validated.executable.as_os_str());
    let mut command_line = wide_string(&build_command_line(
        &validated.executable,
        &validated.request.arguments,
    ));
    let working_directory = wide(validated.working_directory.as_os_str());
    let environment = build_environment_block(&validated.request, &validated.setup)?;
    let created = unsafe {
        CreateProcessAsUserW(
            token,
            executable.as_ptr(),
            command_line.as_mut_ptr(),
            null(),
            null(),
            1,
            CREATE_SUSPENDED | CREATE_UNICODE_ENVIRONMENT | CREATE_NO_WINDOW,
            environment.as_ptr() as *const c_void,
            working_directory.as_ptr(),
            &startup,
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
    let mut restrictions = vec![SID_AND_ATTRIBUTES {
        Sid: account_sid.as_ptr() as PSID,
        Attributes: 0,
    }];
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

fn decrypt_credential(path: &Path) -> Result<Vec<u16>, SandboxFailure> {
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
            0,
            &mut output,
        )
    };
    encrypted.fill(0);
    entropy_bytes.fill(0);
    if decrypted == 0 || output.data.is_null() || output.size == 0 || !output.size.is_multiple_of(2)
    {
        return Err(SandboxFailure::setup(
            "Windows sandbox credential cannot be decrypted by this owner",
        ));
    }
    let password = unsafe {
        std::slice::from_raw_parts(output.data as *const u16, output.size as usize / 2).to_vec()
    };
    unsafe {
        LocalFree(output.data as *mut c_void);
    }
    Ok(password)
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
    if rights_result != 0 || rights & required != required {
        return Err(SandboxFailure::setup(
            "workspace ACL does not grant the prepared sandbox identity",
        ));
    }
    Ok(())
}

fn setup_root() -> Result<PathBuf, SandboxFailure> {
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
        let canonical = fs::canonicalize(&path)
            .map_err(|_| SandboxFailure::setup("Windows sandbox setup path cannot be resolved"))?;
        if !canonical.starts_with(root) {
            return Err(SandboxFailure::setup(
                "Windows sandbox setup path escapes ProgramData",
            ));
        }
        Ok(canonical)
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

fn create_inheritable_output(path: &Path) -> Result<File, SandboxFailure> {
    let file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)
        .map_err(|error| {
            SandboxFailure::new(
                ErrorCode::InternalError,
                format!("failed to create sandbox output capture: {error}"),
            )
        })?;
    set_inheritable(&file)?;
    Ok(file)
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

fn read_bounded(path: &Path, max_bytes: usize) -> (String, bool) {
    let Ok(mut file) = File::open(path) else {
        return (String::new(), false);
    };
    let mut bytes = Vec::with_capacity(max_bytes.min(8_192));
    let mut limited = file.by_ref().take(max_bytes as u64 + 1);
    let _ = limited.read_to_end(&mut bytes);
    let truncated = bytes.len() > max_bytes;
    bytes.truncate(max_bytes);
    (String::from_utf8_lossy(&bytes).into_owned(), truncated)
}

fn file_len(path: &Path) -> u64 {
    fs::metadata(path).map(|value| value.len()).unwrap_or(0)
}

fn active_jobs() -> &'static Mutex<HashMap<String, isize>> {
    ACTIVE_JOBS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn cancelled() -> &'static Mutex<HashSet<String>> {
    CANCELLED.get_or_init(|| Mutex::new(HashSet::new()))
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
    if !valid_identifier(value) {
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
    fn environment_allow_list_excludes_secrets_and_loader_controls() {
        assert!(allowed_environment_key("LANG"));
        assert!(!allowed_environment_key("PATH"));
        assert!(looks_sensitive("OPENAI_API_KEY"));
        assert!(looks_sensitive("session_token"));
    }

    #[test]
    fn windows_execution_fails_closed_until_read_confinement_is_certified() {
        let response = execute(IpcRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: IpcOperation::Execute,
            execution: Some(ExecutionRequest {
                protocol_version: PROTOCOL_VERSION,
                execution_id: "exec-1".into(),
                session_id: "session-1".into(),
                executable: "git".into(),
                arguments: vec!["status".into()],
                working_directory: "C:\\workspace".into(),
                environment: BTreeMap::new(),
                sensitive_environment_keys: BTreeSet::new(),
                profile: PermissionProfile {
                    mode: SandboxMode::WorkspaceWrite,
                    approval_policy: ApprovalPolicy::OnRequest,
                    network_mode: NetworkMode::Off,
                    readable_roots: vec!["C:\\workspace".into()],
                    writable_roots: vec!["C:\\workspace".into()],
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
        assert!(matches!(
            result.error_code,
            Some(ErrorCode::BackendUnavailable)
        ));
        assert!(result
            .error_message
            .as_deref()
            .is_some_and(|message| message.contains("not yet certified")));
    }

    #[test]
    fn windows_status_and_self_test_report_unavailable_reason() {
        let status_response = status();
        let status = status_response.status.expect("status payload");
        assert!(!status.available);
        assert!(matches!(status.backend, SandboxBackend::Unavailable));
        assert!(status
            .message
            .as_deref()
            .is_some_and(|message| message.contains("Docker or WSL2")));

        let self_test_response = self_test();
        let self_test_status = self_test_response.status.expect("self-test status payload");
        assert!(!self_test_status.self_test_passed);
        assert!(matches!(
            self_test_response.error_code,
            Some(ErrorCode::BackendUnavailable)
        ));
    }
}
