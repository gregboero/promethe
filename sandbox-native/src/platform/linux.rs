use std::collections::{HashMap, HashSet};
use std::ffi::CString;
use std::fs::{self, File};
use std::io::{Read, Seek, SeekFrom, Write};
use std::os::fd::{AsRawFd, FromRawFd};
use std::os::unix::fs::{MetadataExt, PermissionsExt};
use std::os::unix::process::CommandExt;
use std::path::{Component, Path, PathBuf};
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use crate::protocol::{
    ErrorCode, ExecutionRequest, ExecutionResult, IpcOperation, IpcRequest, IpcResponse,
    NetworkMode, PermissionProfile, SandboxBackend, SandboxMode, Status, PROTOCOL_VERSION,
};

const MAX_TIMEOUT_MILLIS: u64 = 15 * 60 * 1_000;
const MAX_OUTPUT_BYTES: usize = 10 * 1024 * 1024;
const MAX_MEMORY_BYTES: u64 = 16 * 1024 * 1024 * 1024;
const MAX_PROCESS_LIMIT: u32 = 1_024;
const MAX_ARGUMENTS: usize = 4_096;
const MAX_ARGUMENT_BYTES: usize = 1024 * 1024;
const MAX_ENVIRONMENT_VALUE_BYTES: usize = 8 * 1024;
const POLL_INTERVAL: Duration = Duration::from_millis(10);
const SELF_TEST_TIMEOUT: Duration = Duration::from_secs(5);
const MIN_MEMORY_BYTES: u64 = 16 * 1024 * 1024;
const FIXED_PATH: &str = "/usr/local/bin:/usr/bin:/bin";
const SYSTEM_READ_ROOTS: &[&str] = &[
    "/usr",
    "/bin",
    "/sbin",
    "/lib",
    "/lib64",
    "/nix/store",
    "/etc/alternatives",
    "/etc/ssl",
    "/etc/ca-certificates",
    "/etc/ld.so.cache",
    "/etc/ld.so.conf",
    "/etc/ld.so.conf.d",
    "/etc/passwd",
    "/etc/group",
    "/etc/nsswitch.conf",
    "/etc/localtime",
];

static ACTIVE_EXECUTIONS: OnceLock<Mutex<HashMap<String, i32>>> = OnceLock::new();
static CANCELLED_EXECUTIONS: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();

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
}

struct ValidatedExecution {
    request: ExecutionRequest,
    working_directory: PathBuf,
    readable_roots: Vec<PathBuf>,
    writable_roots: Vec<PathBuf>,
    protected_destinations: Vec<PathBuf>,
}

struct CapturedOutput {
    bytes: Vec<u8>,
    truncated: bool,
}

struct PlaceholderRoot {
    path: PathBuf,
}

impl PlaceholderRoot {
    fn create(execution_id: &str) -> Result<Self, SandboxFailure> {
        let path = std::env::temp_dir().join(format!("promethe-sandbox-{execution_id}"));
        fs::create_dir(&path).map_err(|error| {
            SandboxFailure::new(
                ErrorCode::InternalError,
                format!("failed to create protected-path placeholders: {error}"),
            )
        })?;
        fs::set_permissions(&path, fs::Permissions::from_mode(0o700)).map_err(|error| {
            SandboxFailure::new(
                ErrorCode::InternalError,
                format!("failed to secure protected-path placeholders: {error}"),
            )
        })?;
        Ok(Self { path })
    }

    fn empty_directory(&self, index: usize) -> Result<PathBuf, SandboxFailure> {
        let path = self.path.join(index.to_string());
        fs::create_dir(&path).map_err(|error| {
            SandboxFailure::new(
                ErrorCode::InternalError,
                format!("failed to create a protected-path placeholder: {error}"),
            )
        })?;
        fs::set_permissions(&path, fs::Permissions::from_mode(0o555)).map_err(|error| {
            SandboxFailure::new(
                ErrorCode::InternalError,
                format!("failed to make a protected-path placeholder read-only: {error}"),
            )
        })?;
        Ok(path)
    }
}

impl Drop for PlaceholderRoot {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.path);
    }
}

pub fn execute(request: IpcRequest) -> IpcResponse {
    let validated = match validate_execute_request(request) {
        Ok(validated) => validated,
        Err(error) => return error_response(IpcOperation::Execute, error),
    };

    let bwrap = match verify_backend() {
        Ok(bwrap) => bwrap,
        Err(error) => return error_response(IpcOperation::Execute, error),
    };

    match run_execution(validated, &bwrap) {
        Ok(result) => IpcResponse {
            protocol_version: PROTOCOL_VERSION,
            operation: IpcOperation::Execute,
            execution: Some(result),
            status: None,
            error_code: None,
            error_message: None,
        },
        Err(error) => error_response(IpcOperation::Execute, error),
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
    let execution_id = match request.execution_id {
        Some(value) if valid_identifier(&value) => value,
        _ => {
            return error_response(
                IpcOperation::Cancel,
                SandboxFailure::new(
                    ErrorCode::InvalidRequest,
                    "CANCEL requires a valid executionId",
                ),
            )
        }
    };

    let process_group = active_executions()
        .lock()
        .ok()
        .and_then(|executions| executions.get(&execution_id).copied());
    let Some(process_group) = process_group else {
        return error_response(
            IpcOperation::Cancel,
            SandboxFailure::new(ErrorCode::InvalidRequest, "execution is not active"),
        );
    };

    if let Ok(mut cancelled) = cancelled_executions().lock() {
        cancelled.insert(execution_id);
    }
    terminate_process_group(process_group);

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
    let probe = verify_backend();
    let (available, passed, message) = match probe {
        Ok(_) => (
            true,
            true,
            Some("system bubblewrap self-test passed".into()),
        ),
        Err(error) => (false, false, Some(error.message)),
    };
    IpcResponse {
        protocol_version: PROTOCOL_VERSION,
        operation: IpcOperation::Status,
        execution: None,
        status: Some(Status {
            protocol_version: PROTOCOL_VERSION,
            available,
            backend: SandboxBackend::LinuxBwrap,
            mode: SandboxMode::WorkspaceWrite,
            network_mode: NetworkMode::Off,
            degraded: false,
            setup_required: !available,
            self_test_passed: passed,
            message,
        }),
        error_code: None,
        error_message: None,
    }
}

pub fn self_test() -> IpcResponse {
    match verify_backend() {
        Ok(_) => IpcResponse {
            protocol_version: PROTOCOL_VERSION,
            operation: IpcOperation::SelfTest,
            execution: None,
            status: Some(Status {
                protocol_version: PROTOCOL_VERSION,
                available: true,
                backend: SandboxBackend::LinuxBwrap,
                mode: SandboxMode::WorkspaceWrite,
                network_mode: NetworkMode::Off,
                degraded: false,
                setup_required: false,
                self_test_passed: true,
                message: Some("system bubblewrap self-test passed".into()),
            }),
            error_code: None,
            error_message: None,
        },
        Err(error) => error_response(IpcOperation::SelfTest, error),
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
    validate_command(&execution)?;
    validate_profile(&execution.profile)?;

    let readable_roots = canonical_roots(&execution.profile.readable_roots, "readable root")?;
    let writable_roots = canonical_roots(&execution.profile.writable_roots, "writable root")?;
    if readable_roots.is_empty() && writable_roots.is_empty() {
        return Err(SandboxFailure::new(
            ErrorCode::WorkspaceViolation,
            "at least one workspace root is required",
        ));
    }
    if matches!(execution.profile.mode, SandboxMode::ReadOnly) && !writable_roots.is_empty() {
        return Err(SandboxFailure::new(
            ErrorCode::PolicyDenied,
            "READ_ONLY cannot contain writable roots",
        ));
    }
    if matches!(execution.profile.mode, SandboxMode::WorkspaceWrite)
        && writable_roots
            .iter()
            .any(|root| !readable_roots.is_empty() && !is_within_any(root, &readable_roots))
    {
        return Err(SandboxFailure::new(
            ErrorCode::WorkspaceViolation,
            "every writable root must be contained by a readable root",
        ));
    }

    let working_directory =
        canonical_directory(Path::new(&execution.working_directory), "working directory")?;
    let mut all_roots = readable_roots.clone();
    all_roots.extend(writable_roots.iter().cloned());
    if !is_within_any(&working_directory, &all_roots) {
        return Err(SandboxFailure::new(
            ErrorCode::WorkspaceViolation,
            "working directory is outside the workspace roots",
        ));
    }

    let protected_destinations = validate_protected_paths(&execution.profile, &writable_roots)?;

    Ok(ValidatedExecution {
        request: execution,
        working_directory,
        readable_roots,
        writable_roots,
        protected_destinations,
    })
}

fn validate_command(execution: &ExecutionRequest) -> Result<(), SandboxFailure> {
    validate_text("executable", &execution.executable, 4_096)?;
    crate::command_policy::validate_direct_command(&execution.executable, &execution.arguments)
        .map_err(|message| SandboxFailure::new(ErrorCode::PolicyDenied, message))?;
    let executable_path = Path::new(&execution.executable);
    if executable_path.components().count() > 1 && !executable_path.is_absolute() {
        return Err(SandboxFailure::new(
            ErrorCode::InvalidRequest,
            "executable must be a bare name or an absolute path",
        ));
    }
    if execution.arguments.len() > MAX_ARGUMENTS {
        return Err(SandboxFailure::new(
            ErrorCode::InvalidRequest,
            "too many command arguments",
        ));
    }
    let mut argument_bytes = 0usize;
    for argument in &execution.arguments {
        validate_text("argument", argument, 64 * 1024)?;
        argument_bytes = argument_bytes.saturating_add(argument.len());
    }
    if argument_bytes > MAX_ARGUMENT_BYTES {
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
            "sensitive environment values are not accepted by the Linux sandbox",
        ));
    }
    for (key, value) in &execution.environment {
        if !allowed_environment_key(key) {
            return Err(SandboxFailure::new(
                ErrorCode::PolicyDenied,
                format!("environment variable {key} is not allowed"),
            ));
        }
        validate_text("environment value", value, MAX_ENVIRONMENT_VALUE_BYTES)?;
    }
    Ok(())
}

fn validate_profile(profile: &PermissionProfile) -> Result<(), SandboxFailure> {
    if !matches!(profile.network_mode, NetworkMode::Off) || !profile.allowed_domains.is_empty() {
        return Err(SandboxFailure::new(
            ErrorCode::NetworkDenied,
            "the Linux backend only supports network mode OFF",
        ));
    }
    if matches!(profile.mode, SandboxMode::FullAccess) {
        return Err(SandboxFailure::new(
            ErrorCode::PolicyDenied,
            "FULL_ACCESS cannot be executed by the sandbox helper",
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

fn canonical_roots(values: &[String], label: &str) -> Result<Vec<PathBuf>, SandboxFailure> {
    let mut roots = Vec::new();
    for value in values {
        validate_text(label, value, 4_096)?;
        let root = canonical_directory(Path::new(value), label)?;
        if root == Path::new("/") {
            return Err(SandboxFailure::new(
                ErrorCode::WorkspaceViolation,
                format!("{label} cannot be the filesystem root"),
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
    if !canonical.is_dir() || canonical.to_str().is_none() {
        return Err(SandboxFailure::new(
            ErrorCode::WorkspaceViolation,
            format!("{label} must resolve to a UTF-8 directory"),
        ));
    }
    Ok(canonical)
}

fn validate_protected_paths(
    profile: &PermissionProfile,
    writable_roots: &[PathBuf],
) -> Result<Vec<PathBuf>, SandboxFailure> {
    let mut destinations = Vec::new();
    for value in &profile.protected_paths {
        validate_text("protected path", value, 4_096)?;
        let path = Path::new(value);
        if path.is_absolute() {
            let destination = validate_protected_destination(path, writable_roots)?;
            if !destinations.contains(&destination) {
                destinations.push(destination);
            }
            continue;
        }
        if path
            .components()
            .any(|component| !matches!(component, Component::Normal(_)))
        {
            return Err(SandboxFailure::new(
                ErrorCode::InvalidRequest,
                "protected paths must be absolute or clean workspace-relative paths",
            ));
        }
        for root in writable_roots {
            let destination = root.join(path);
            ensure_no_symlink_components(root, path)?;
            if !destinations.contains(&destination) {
                destinations.push(destination);
            }
        }
    }
    Ok(destinations)
}

fn validate_protected_destination(
    path: &Path,
    writable_roots: &[PathBuf],
) -> Result<PathBuf, SandboxFailure> {
    if !is_within_any(path, writable_roots) {
        return Err(SandboxFailure::new(
            ErrorCode::WorkspaceViolation,
            "absolute protected path is outside writable roots",
        ));
    }
    for root in writable_roots {
        if let Ok(relative) = path.strip_prefix(root) {
            ensure_no_symlink_components(root, relative)?;
        }
    }
    Ok(path.to_path_buf())
}

fn ensure_no_symlink_components(root: &Path, relative: &Path) -> Result<(), SandboxFailure> {
    let mut current = root.to_path_buf();
    for component in relative.components() {
        current.push(component.as_os_str());
        match fs::symlink_metadata(&current) {
            Ok(metadata) if metadata.file_type().is_symlink() => {
                return Err(SandboxFailure::new(
                    ErrorCode::WorkspaceViolation,
                    "protected path traverses a symbolic link",
                ))
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => break,
            Err(_) => {
                return Err(SandboxFailure::new(
                    ErrorCode::WorkspaceViolation,
                    "protected path cannot be inspected",
                ))
            }
        }
    }
    Ok(())
}

fn run_execution(
    validated: ValidatedExecution,
    bwrap: &Path,
) -> Result<ExecutionResult, SandboxFailure> {
    let execution_id = validated.request.execution_id.clone();
    {
        let mut active = active_executions().lock().map_err(|_| {
            SandboxFailure::new(
                ErrorCode::InternalError,
                "execution registry is unavailable",
            )
        })?;
        if active.contains_key(&execution_id) {
            return Err(SandboxFailure::new(
                ErrorCode::InvalidRequest,
                "executionId is already active",
            ));
        }
        // Reserve the identifier before doing any filesystem setup.
        active.insert(execution_id.clone(), 0);
    }

    let result = run_reserved_execution(validated, bwrap);
    if let Ok(mut active) = active_executions().lock() {
        active.remove(&execution_id);
    }
    result
}

fn run_reserved_execution(
    validated: ValidatedExecution,
    bwrap: &Path,
) -> Result<ExecutionResult, SandboxFailure> {
    let execution_id = validated.request.execution_id.clone();
    let placeholder_root = PlaceholderRoot::create(&execution_id)?;
    let protected_binds = protected_binds(&validated.protected_destinations, &placeholder_root)?;
    let seccomp = create_seccomp_filter()?;
    let mut command = build_bwrap_command(&validated, bwrap, &protected_binds, &seccomp)?;
    let started = Instant::now();
    let mut child = command.spawn().map_err(|error| {
        SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            format!("failed to start the bubblewrap sandbox: {error}"),
        )
    })?;
    let process_group = child.id() as i32;
    if let Ok(mut active) = active_executions().lock() {
        active.insert(execution_id.clone(), process_group);
    }
    if cancelled_executions()
        .lock()
        .ok()
        .map(|values| values.contains(&execution_id))
        .unwrap_or(false)
    {
        terminate_process_group(process_group);
    }

    let stdout = child.stdout.take().ok_or_else(|| {
        SandboxFailure::new(ErrorCode::InternalError, "sandbox stdout was not captured")
    })?;
    let stderr = child.stderr.take().ok_or_else(|| {
        SandboxFailure::new(ErrorCode::InternalError, "sandbox stderr was not captured")
    })?;
    let output_limit = validated.request.profile.limits.max_output_bytes_per_stream;
    let stdout_reader = thread::spawn(move || capture_stream(stdout, output_limit));
    let stderr_reader = thread::spawn(move || capture_stream(stderr, output_limit));

    let timeout = Duration::from_millis(validated.request.profile.limits.timeout_millis);
    let (exit_status, timed_out) = wait_for_child(&mut child, timeout, process_group)?;
    // Kill any daemonized descendants that outlived the requested command.
    terminate_process_group(process_group);

    let cancelled = cancelled_executions()
        .lock()
        .ok()
        .map(|mut values| values.remove(&execution_id))
        .unwrap_or(false);
    let stdout = stdout_reader.join().map_err(|_| {
        SandboxFailure::new(ErrorCode::InternalError, "stdout capture thread failed")
    })?;
    let stderr = stderr_reader.join().map_err(|_| {
        SandboxFailure::new(ErrorCode::InternalError, "stderr capture thread failed")
    })?;
    let truncated = stdout.truncated || stderr.truncated;

    Ok(ExecutionResult {
        protocol_version: PROTOCOL_VERSION,
        execution_id,
        exit_code: exit_status.and_then(|status| status.code()),
        stdout: String::from_utf8_lossy(&stdout.bytes).into_owned(),
        stderr: String::from_utf8_lossy(&stderr.bytes).into_owned(),
        timed_out,
        cancelled,
        truncated,
        duration_millis: started.elapsed().as_millis().min(u64::MAX as u128) as u64,
        error_code: if cancelled {
            Some(ErrorCode::Cancelled)
        } else if timed_out {
            Some(ErrorCode::TimedOut)
        } else {
            None
        },
        error_message: if cancelled {
            Some("sandbox execution was cancelled".into())
        } else if timed_out {
            Some("sandbox execution exceeded its timeout".into())
        } else {
            None
        },
    })
}

fn protected_binds(
    destinations: &[PathBuf],
    placeholders: &PlaceholderRoot,
) -> Result<Vec<(PathBuf, PathBuf)>, SandboxFailure> {
    let mut binds = Vec::new();
    for (index, destination) in destinations.iter().enumerate() {
        let source = match fs::symlink_metadata(destination) {
            Ok(metadata) if metadata.file_type().is_symlink() => {
                return Err(SandboxFailure::new(
                    ErrorCode::WorkspaceViolation,
                    "protected path became a symbolic link",
                ))
            }
            Ok(_) => destination.clone(),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
                placeholders.empty_directory(index)?
            }
            Err(_) => {
                return Err(SandboxFailure::new(
                    ErrorCode::WorkspaceViolation,
                    "protected path cannot be inspected",
                ))
            }
        };
        binds.push((source, destination.clone()));
    }
    Ok(binds)
}

fn build_bwrap_command(
    validated: &ValidatedExecution,
    bwrap: &Path,
    protected_binds: &[(PathBuf, PathBuf)],
    seccomp: &File,
) -> Result<Command, SandboxFailure> {
    let mut command = Command::new(bwrap);
    command
        .args([
            "--die-with-parent",
            "--new-session",
            "--unshare-user",
            "--unshare-pid",
            "--unshare-net",
            "--unshare-ipc",
            "--unshare-uts",
            "--unshare-cgroup-try",
            "--disable-userns",
            "--cap-drop",
            "ALL",
            "--tmpfs",
            "/",
            "--proc",
            "/proc",
            "--dev",
            "/dev",
            "--tmpfs",
            "/tmp",
            "--tmpfs",
            "/run",
            "--dir",
            "/tmp/promethe-home",
            "--clearenv",
            "--setenv",
            "PATH",
            FIXED_PATH,
            "--setenv",
            "HOME",
            "/tmp/promethe-home",
            "--setenv",
            "TMPDIR",
            "/tmp",
            "--setenv",
            "USER",
            "sandbox",
            "--setenv",
            "LOGNAME",
            "sandbox",
        ])
        .arg("--seccomp")
        .arg(seccomp.as_raw_fd().to_string());

    for root in SYSTEM_READ_ROOTS {
        let source = Path::new(root);
        if source.exists() {
            command.arg("--ro-bind").arg(source).arg(source);
        }
    }
    for root in &validated.readable_roots {
        command.arg("--ro-bind").arg(root).arg(root);
    }
    for root in &validated.writable_roots {
        command.arg("--bind").arg(root).arg(root);
    }
    for (source, destination) in protected_binds {
        command.arg("--ro-bind").arg(source).arg(destination);
    }
    for (key, value) in &validated.request.environment {
        command.arg("--setenv").arg(key).arg(value);
    }
    command
        .arg("--chdir")
        .arg(&validated.working_directory)
        .arg("--")
        .arg(&validated.request.executable)
        .args(&validated.request.arguments)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .env_clear();

    let limits = validated.request.profile.limits.clone();
    unsafe {
        command.pre_exec(move || {
            if libc::setsid() == -1 {
                return Err(std::io::Error::last_os_error());
            }
            if libc::prctl(libc::PR_SET_PDEATHSIG, libc::SIGKILL) == -1 {
                return Err(std::io::Error::last_os_error());
            }
            if libc::getppid() == 1 {
                return Err(std::io::Error::new(
                    std::io::ErrorKind::Interrupted,
                    "sandbox parent exited before launch",
                ));
            }
            set_resource_limit(libc::RLIMIT_AS, limits.memory_bytes)?;
            set_resource_limit(libc::RLIMIT_NPROC, limits.process_limit as u64)?;
            set_resource_limit(libc::RLIMIT_NOFILE, 256)?;
            let cpu_seconds = ((limits.timeout_millis as f64 / 1000.0) * limits.cpu_limit)
                .ceil()
                .max(1.0) as u64;
            set_resource_limit(libc::RLIMIT_CPU, cpu_seconds)?;
            Ok(())
        });
    }
    Ok(command)
}

unsafe fn set_resource_limit(
    resource: libc::__rlimit_resource_t,
    value: u64,
) -> std::io::Result<()> {
    let limit = libc::rlimit {
        rlim_cur: value as libc::rlim_t,
        rlim_max: value as libc::rlim_t,
    };
    if libc::setrlimit(resource, &limit) == -1 {
        Err(std::io::Error::last_os_error())
    } else {
        Ok(())
    }
}

fn wait_for_child(
    child: &mut Child,
    timeout: Duration,
    process_group: i32,
) -> Result<(Option<ExitStatus>, bool), SandboxFailure> {
    let started = Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return Ok((Some(status), false)),
            Ok(None) if started.elapsed() < timeout => thread::sleep(POLL_INTERVAL),
            Ok(None) => {
                terminate_process_group(process_group);
                return child
                    .wait()
                    .map(|status| (Some(status), true))
                    .map_err(|error| {
                        SandboxFailure::new(
                            ErrorCode::InternalError,
                            format!("failed to reap timed-out sandbox: {error}"),
                        )
                    });
            }
            Err(error) => {
                terminate_process_group(process_group);
                return Err(SandboxFailure::new(
                    ErrorCode::InternalError,
                    format!("failed while waiting for sandbox: {error}"),
                ));
            }
        }
    }
}

fn capture_stream<R: Read>(mut stream: R, limit: usize) -> CapturedOutput {
    let mut bytes = Vec::with_capacity(limit.min(64 * 1024));
    let mut buffer = [0u8; 8 * 1024];
    let mut truncated = false;
    loop {
        match stream.read(&mut buffer) {
            Ok(0) | Err(_) => break,
            Ok(count) => {
                let remaining = limit.saturating_sub(bytes.len());
                let retained = remaining.min(count);
                bytes.extend_from_slice(&buffer[..retained]);
                truncated |= retained < count;
            }
        }
    }
    CapturedOutput { bytes, truncated }
}

fn terminate_process_group(process_group: i32) {
    if process_group > 0 {
        unsafe {
            libc::kill(-process_group, libc::SIGKILL);
        }
    }
}

fn verify_backend() -> Result<PathBuf, SandboxFailure> {
    let bwrap = find_system_bwrap()?;
    run_bwrap_self_test(&bwrap)?;
    Ok(bwrap)
}

fn find_system_bwrap() -> Result<PathBuf, SandboxFailure> {
    for candidate in ["/usr/bin/bwrap", "/bin/bwrap", "/usr/local/bin/bwrap"] {
        let path = Path::new(candidate);
        let Ok(canonical) = fs::canonicalize(path) else {
            continue;
        };
        let metadata = fs::metadata(&canonical).map_err(|_| {
            SandboxFailure::new(
                ErrorCode::BackendUnavailable,
                "system bubblewrap metadata cannot be read",
            )
        })?;
        if !metadata.is_file()
            || metadata.uid() != 0
            || metadata.mode() & 0o022 != 0
            || metadata.mode() & 0o111 == 0
        {
            continue;
        }
        return Ok(canonical);
    }
    Err(SandboxFailure::new(
        ErrorCode::SetupRequired,
        "a root-owned, non-writable system bubblewrap binary is required",
    ))
}

fn run_bwrap_self_test(bwrap: &Path) -> Result<(), SandboxFailure> {
    let seccomp = create_seccomp_filter()?;
    let mut command = Command::new(bwrap);
    command.args([
        "--die-with-parent",
        "--new-session",
        "--unshare-user",
        "--unshare-pid",
        "--unshare-net",
        "--unshare-ipc",
        "--unshare-uts",
        "--unshare-cgroup-try",
        "--disable-userns",
        "--cap-drop",
        "ALL",
        "--tmpfs",
        "/",
        "--proc",
        "/proc",
        "--dev",
        "/dev",
        "--tmpfs",
        "/tmp",
        "--tmpfs",
        "/run",
        "--clearenv",
        "--setenv",
        "PATH",
        FIXED_PATH,
    ]);
    for root in SYSTEM_READ_ROOTS {
        let source = Path::new(root);
        if source.exists() {
            command.arg("--ro-bind").arg(source).arg(source);
        }
    }
    let mut child = command
        .arg("--seccomp")
        .arg(seccomp.as_raw_fd().to_string())
        .args(["--", "/bin/true"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .env_clear()
        .spawn()
        .map_err(|error| {
            SandboxFailure::new(
                ErrorCode::BackendUnavailable,
                format!("bubblewrap self-test could not start: {error}"),
            )
        })?;
    let started = Instant::now();
    loop {
        match child.try_wait() {
            Ok(Some(status)) if status.success() => return Ok(()),
            Ok(Some(_)) => {
                let mut stderr = String::new();
                if let Some(stream) = child.stderr.take() {
                    let _ = stream.take(4_096).read_to_string(&mut stderr);
                }
                let summary = stderr
                    .lines()
                    .next()
                    .unwrap_or("unknown bubblewrap failure");
                return Err(SandboxFailure::new(
                    ErrorCode::SetupRequired,
                    format!("bubblewrap self-test failed: {summary}"),
                ));
            }
            Ok(None) if started.elapsed() < SELF_TEST_TIMEOUT => thread::sleep(POLL_INTERVAL),
            Ok(None) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(SandboxFailure::new(
                    ErrorCode::BackendUnavailable,
                    "bubblewrap self-test timed out",
                ));
            }
            Err(error) => {
                let _ = child.kill();
                return Err(SandboxFailure::new(
                    ErrorCode::BackendUnavailable,
                    format!("bubblewrap self-test failed while waiting: {error}"),
                ));
            }
        }
    }
}

#[repr(C)]
#[derive(Clone, Copy)]
struct SockFilter {
    code: u16,
    jt: u8,
    jf: u8,
    k: u32,
}

fn create_seccomp_filter() -> Result<File, SandboxFailure> {
    let architecture = audit_architecture().ok_or_else(|| {
        SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            "seccomp is unsupported on this Linux architecture",
        )
    })?;
    let mut filters = vec![
        bpf_statement(0x20, 4),
        bpf_jump(0x15, architecture, 1, 0),
        bpf_statement(0x06, 0x8000_0000),
        bpf_statement(0x20, 0),
    ];
    for syscall in denied_syscalls() {
        filters.push(bpf_jump(0x15, syscall as u32, 0, 1));
        filters.push(bpf_statement(0x06, 0x0005_0000 | libc::EPERM as u32));
    }
    filters.push(bpf_statement(0x06, 0x7fff_0000));

    let name = CString::new("promethe-seccomp").expect("static memfd name is valid");
    let descriptor = unsafe { libc::memfd_create(name.as_ptr(), libc::MFD_CLOEXEC) };
    if descriptor < 0 {
        return Err(SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            format!(
                "failed to allocate seccomp program: {}",
                std::io::Error::last_os_error()
            ),
        ));
    }
    let mut file = unsafe { File::from_raw_fd(descriptor) };
    let bytes = unsafe {
        std::slice::from_raw_parts(
            filters.as_ptr() as *const u8,
            filters.len() * std::mem::size_of::<SockFilter>(),
        )
    };
    file.write_all(bytes).map_err(|error| {
        SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            format!("failed to write seccomp program: {error}"),
        )
    })?;
    file.seek(SeekFrom::Start(0)).map_err(|error| {
        SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            format!("failed to rewind seccomp program: {error}"),
        )
    })?;
    let flags = unsafe { libc::fcntl(descriptor, libc::F_GETFD) };
    if flags < 0 || unsafe { libc::fcntl(descriptor, libc::F_SETFD, flags & !libc::FD_CLOEXEC) } < 0
    {
        return Err(SandboxFailure::new(
            ErrorCode::BackendUnavailable,
            "failed to pass seccomp program to bubblewrap",
        ));
    }
    Ok(file)
}

const fn bpf_statement(code: u16, k: u32) -> SockFilter {
    SockFilter {
        code,
        jt: 0,
        jf: 0,
        k,
    }
}

const fn bpf_jump(code: u16, k: u32, jt: u8, jf: u8) -> SockFilter {
    SockFilter { code, jt, jf, k }
}

#[cfg(target_arch = "x86_64")]
const fn audit_architecture() -> Option<u32> {
    Some(0xC000_003E)
}

#[cfg(target_arch = "aarch64")]
const fn audit_architecture() -> Option<u32> {
    Some(0xC000_00B7)
}

#[cfg(not(any(target_arch = "x86_64", target_arch = "aarch64")))]
const fn audit_architecture() -> Option<u32> {
    None
}

#[cfg(any(target_arch = "x86_64", target_arch = "aarch64"))]
fn denied_syscalls() -> Vec<libc::c_long> {
    vec![
        libc::SYS_mount,
        libc::SYS_umount2,
        libc::SYS_pivot_root,
        libc::SYS_ptrace,
        libc::SYS_bpf,
        libc::SYS_perf_event_open,
        libc::SYS_keyctl,
        libc::SYS_add_key,
        libc::SYS_request_key,
        libc::SYS_init_module,
        libc::SYS_finit_module,
        libc::SYS_delete_module,
        libc::SYS_kexec_load,
        libc::SYS_open_by_handle_at,
        libc::SYS_userfaultfd,
        libc::SYS_process_vm_readv,
        libc::SYS_process_vm_writev,
        libc::SYS_socket,
        libc::SYS_connect,
        libc::SYS_bind,
        libc::SYS_listen,
        libc::SYS_accept,
        libc::SYS_accept4,
    ]
}

#[cfg(not(any(target_arch = "x86_64", target_arch = "aarch64")))]
fn denied_syscalls() -> Vec<libc::c_long> {
    Vec::new()
}

fn active_executions() -> &'static Mutex<HashMap<String, i32>> {
    ACTIVE_EXECUTIONS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn cancelled_executions() -> &'static Mutex<HashSet<String>> {
    CANCELLED_EXECUTIONS.get_or_init(|| Mutex::new(HashSet::new()))
}

fn is_within_any(path: &Path, roots: &[PathBuf]) -> bool {
    roots.iter().any(|root| path.starts_with(root))
}

fn validate_identifier(label: &str, value: &str) -> Result<(), SandboxFailure> {
    if !valid_identifier(value) {
        return Err(SandboxFailure::new(
            ErrorCode::InvalidRequest,
            format!("{label} must be a UUID or a restricted identifier"),
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

fn validate_text(label: &str, value: &str, max_bytes: usize) -> Result<(), SandboxFailure> {
    if value.is_empty() || value.len() > max_bytes || value.contains('\0') {
        return Err(SandboxFailure::new(
            ErrorCode::InvalidRequest,
            format!("{label} is empty, too large, or contains a NUL byte"),
        ));
    }
    Ok(())
}

fn allowed_environment_key(key: &str) -> bool {
    matches!(
        key,
        "LANG" | "LC_ALL" | "LC_CTYPE" | "TERM" | "TZ" | "NO_COLOR" | "FORCE_COLOR" | "CI"
    )
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
    use crate::protocol::{ApprovalPolicy, ResourceLimits};
    use std::collections::{BTreeMap, BTreeSet};

    fn test_root() -> PathBuf {
        let root =
            std::env::temp_dir().join(format!("promethe-linux-test-{}", uuid::Uuid::new_v4()));
        fs::create_dir(&root).unwrap();
        root
    }

    fn request(root: &Path) -> IpcRequest {
        IpcRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: IpcOperation::Execute,
            execution: Some(ExecutionRequest {
                protocol_version: PROTOCOL_VERSION,
                execution_id: uuid::Uuid::new_v4().to_string(),
                session_id: "session-1".into(),
                executable: "true".into(),
                arguments: Vec::new(),
                working_directory: root.to_string_lossy().into_owned(),
                environment: BTreeMap::new(),
                sensitive_environment_keys: BTreeSet::new(),
                profile: PermissionProfile {
                    mode: SandboxMode::WorkspaceWrite,
                    approval_policy: ApprovalPolicy::OnRequest,
                    network_mode: NetworkMode::Off,
                    readable_roots: vec![root.to_string_lossy().into_owned()],
                    writable_roots: vec![root.to_string_lossy().into_owned()],
                    protected_paths: vec![".git".into()],
                    allowed_domains: Vec::new(),
                    limits: ResourceLimits {
                        timeout_millis: 1_000,
                        max_output_bytes_per_stream: 1_024,
                        memory_bytes: 64 * 1024 * 1024,
                        cpu_limit: 1.0,
                        process_limit: 16,
                    },
                },
                interactive: false,
            }),
            execution_id: None,
        }
    }

    #[test]
    fn accepts_workspace_request_and_reserves_missing_protected_path() {
        let root = test_root();
        let validated = validate_execute_request(request(&root)).unwrap();
        assert_eq!(validated.working_directory, root);
        assert_eq!(validated.protected_destinations.len(), 1);
        fs::remove_dir_all(validated.working_directory).unwrap();
    }

    #[test]
    fn rejects_network_allowlist_fail_closed() {
        let root = test_root();
        let mut value = request(&root);
        let execution = value.execution.as_mut().unwrap();
        execution.profile.network_mode = NetworkMode::Allowlist;
        execution.profile.allowed_domains = vec!["example.com".into()];
        assert!(matches!(
            validate_execute_request(value),
            Err(SandboxFailure {
                code: ErrorCode::NetworkDenied,
                ..
            })
        ));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn rejects_full_access_and_sensitive_environment() {
        let root = test_root();
        let mut full_access = request(&root);
        full_access.execution.as_mut().unwrap().profile.mode = SandboxMode::FullAccess;
        assert!(validate_execute_request(full_access).is_err());

        let mut sensitive = request(&root);
        sensitive
            .execution
            .as_mut()
            .unwrap()
            .sensitive_environment_keys
            .insert("TOKEN".into());
        assert!(validate_execute_request(sensitive).is_err());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn rejects_protected_path_traversal() {
        let root = test_root();
        let mut value = request(&root);
        value.execution.as_mut().unwrap().profile.protected_paths = vec!["../outside".into()];
        assert!(validate_execute_request(value).is_err());
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn rejects_shell_execution_before_starting_bubblewrap() {
        let root = test_root();
        let mut value = request(&root);
        let execution = value.execution.as_mut().unwrap();
        execution.executable = "/bin/bash".into();
        execution.arguments = vec!["-c".into(), "id".into()];
        assert!(matches!(
            validate_execute_request(value),
            Err(SandboxFailure {
                code: ErrorCode::PolicyDenied,
                ..
            })
        ));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn bounded_capture_drains_and_truncates() {
        let input = std::io::Cursor::new(vec![b'x'; 64]);
        let captured = capture_stream(input, 8);
        assert_eq!(captured.bytes.len(), 8);
        assert!(captured.truncated);
    }
}
