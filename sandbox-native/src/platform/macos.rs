//! macOS Seatbelt backend.
//!
//! The profile is deliberately deny-by-default. Paths are resolved before they
//! reach the profile generator and every interpolated string is quoted as an
//! SBPL literal, so a workspace name can never inject an additional rule.

use std::collections::{HashMap, HashSet};
use std::fs;
use std::io::{self, Read};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::{Duration, Instant};

use crate::protocol::{
    ErrorCode, ExecutionRequest, ExecutionResult, IpcOperation, IpcRequest, IpcResponse,
    NetworkMode, SandboxBackend, SandboxMode, Status, PROTOCOL_VERSION,
};

const SEATBELT_EXECUTABLE: &str = "/usr/bin/sandbox-exec";
const MINIMAL_PATH: &str = "/usr/bin:/bin:/usr/sbin:/sbin";
const SYSTEM_READ_ROOTS: &[&str] = &["/System", "/usr", "/bin", "/sbin"];
const PROCESS_TREE_CONFINEMENT_UNCERTIFIED: &str =
    "macOS execution is unavailable until detached descendant confinement is certified";

static ACTIVE_PROCESS_GROUPS: OnceLock<Mutex<HashMap<String, i32>>> = OnceLock::new();
static CANCELLED_EXECUTIONS: OnceLock<Mutex<HashSet<String>>> = OnceLock::new();

fn active_process_groups() -> &'static Mutex<HashMap<String, i32>> {
    ACTIVE_PROCESS_GROUPS.get_or_init(|| Mutex::new(HashMap::new()))
}

fn cancelled_executions() -> &'static Mutex<HashSet<String>> {
    CANCELLED_EXECUTIONS.get_or_init(|| Mutex::new(HashSet::new()))
}

#[derive(Debug, Clone)]
pub struct SeatbeltProfileInput {
    pub mode: SandboxMode,
    pub network_mode: NetworkMode,
    pub readable_roots: Vec<PathBuf>,
    pub writable_roots: Vec<PathBuf>,
    pub protected_paths: Vec<PathBuf>,
}

#[derive(Debug)]
struct ResolvedExecution {
    executable: String,
    arguments: Vec<String>,
    working_directory: PathBuf,
    environment: Vec<(String, String)>,
    profile: SeatbeltProfileInput,
    request: ExecutionRequest,
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
        PROCESS_TREE_CONFINEMENT_UNCERTIFIED.into(),
    )
}

#[allow(dead_code)]
fn execute_uncertified(request: IpcRequest) -> IpcResponse {
    let Some(execution) = request.execution else {
        return execution_error(
            IpcOperation::Execute,
            ErrorCode::InvalidRequest,
            "execute requests require an execution payload",
        );
    };

    if !seatbelt_available() {
        return execution_error(
            IpcOperation::Execute,
            ErrorCode::BackendUnavailable,
            "macOS Seatbelt is unavailable: /usr/bin/sandbox-exec is required",
        );
    }

    let resolved = match resolve_execution(execution) {
        Ok(resolved) => resolved,
        Err((code, message)) => return execution_error(IpcOperation::Execute, code, &message),
    };
    let profile = match build_seatbelt_profile(&resolved.profile) {
        Ok(profile) => profile,
        Err((code, message)) => return execution_error(IpcOperation::Execute, code, &message),
    };

    let started = Instant::now();
    let mut command = Command::new(SEATBELT_EXECUTABLE);
    command
        .arg("-p")
        .arg(profile)
        .arg("--")
        .arg(&resolved.executable)
        .args(&resolved.arguments)
        .current_dir(&resolved.working_directory)
        .env_clear()
        .env("PATH", MINIMAL_PATH)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    for (key, value) in &resolved.environment {
        command.env(key, value);
    }

    let limits = resolved.request.profile.limits.clone();
    let child_limits = limits.clone();
    // A dedicated process group lets timeout and cancel terminate descendants,
    // not only sandbox-exec itself.
    unsafe {
        command.pre_exec(move || {
            apply_resource_limits(&child_limits)?;
            if libc::setpgid(0, 0) != 0 {
                return Err(io::Error::last_os_error());
            }
            Ok(())
        });
    }

    let mut child = match command.spawn() {
        Ok(child) => child,
        Err(error) => {
            return execution_error(
                IpcOperation::Execute,
                ErrorCode::BackendUnavailable,
                &format!("could not start macOS Seatbelt: {error}"),
            );
        }
    };
    let pid = child.id() as i32;
    let execution_id = resolved.request.execution_id.clone();
    active_process_groups()
        .lock()
        .expect("active sandbox process registry poisoned")
        .insert(execution_id.clone(), pid);

    let stdout = child.stdout.take().map(|stream| {
        read_bounded(
            stream,
            resolved.request.profile.limits.max_output_bytes_per_stream,
        )
    });
    let stderr = child.stderr.take().map(|stream| {
        read_bounded(
            stream,
            resolved.request.profile.limits.max_output_bytes_per_stream,
        )
    });
    let (status, wait_timed_out) = wait_with_timeout(&mut child, pid, limits.timeout_millis);
    // The main child may exit while descendants keep inherited pipes and the
    // sandbox alive. Always terminate the dedicated group, even after success.
    terminate_process_group(pid);
    wait_for_process_group_exit(pid, Duration::from_secs(1));
    active_process_groups()
        .lock()
        .expect("active sandbox process registry poisoned")
        .remove(&execution_id);
    let cancelled = cancelled_executions()
        .lock()
        .expect("cancelled sandbox registry poisoned")
        .remove(&execution_id);

    let stdout = collect_output(stdout);
    let stderr = collect_output(stderr);
    let duration_millis = started.elapsed().as_millis() as u64;
    let timed_out = wait_timed_out && !cancelled;
    let error_code = if cancelled {
        Some(ErrorCode::Cancelled)
    } else if timed_out {
        Some(ErrorCode::TimedOut)
    } else {
        None
    };
    let error_message = if cancelled {
        Some("sandbox execution was cancelled".into())
    } else if timed_out {
        Some("sandbox execution exceeded its time limit".into())
    } else {
        None
    };

    IpcResponse {
        protocol_version: PROTOCOL_VERSION,
        operation: IpcOperation::Execute,
        execution: Some(ExecutionResult {
            protocol_version: PROTOCOL_VERSION,
            execution_id,
            exit_code: status.and_then(|status| status.code()),
            stdout: stdout.text,
            stderr: stderr.text,
            timed_out,
            cancelled,
            truncated: stdout.truncated || stderr.truncated,
            duration_millis,
            error_code,
            error_message,
        }),
        status: None,
        error_code: None,
        error_message: None,
    }
}

pub fn cancel(request: IpcRequest) -> IpcResponse {
    let Some(execution_id) = request.execution_id else {
        return execution_error(
            IpcOperation::Cancel,
            ErrorCode::InvalidRequest,
            "cancel requests require an execution id",
        );
    };
    let pid = active_process_groups()
        .lock()
        .expect("active sandbox process registry poisoned")
        .get(&execution_id)
        .copied();
    match pid {
        Some(pid) => {
            cancelled_executions()
                .lock()
                .expect("cancelled sandbox registry poisoned")
                .insert(execution_id);
            terminate_process_group(pid);
            IpcResponse {
                protocol_version: PROTOCOL_VERSION,
                operation: IpcOperation::Cancel,
                execution: None,
                status: None,
                error_code: None,
                error_message: None,
            }
        }
        None => execution_error(
            IpcOperation::Cancel,
            ErrorCode::InvalidRequest,
            "sandbox execution is not active",
        ),
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
            mode: SandboxMode::ReadOnly,
            network_mode: NetworkMode::Off,
            degraded: false,
            setup_required: false,
            self_test_passed: false,
            message: Some(PROCESS_TREE_CONFINEMENT_UNCERTIFIED.into()),
        }),
        error_code: Some(ErrorCode::BackendUnavailable),
        error_message: Some(PROCESS_TREE_CONFINEMENT_UNCERTIFIED.into()),
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
            mode: SandboxMode::ReadOnly,
            network_mode: NetworkMode::Off,
            degraded: false,
            setup_required: false,
            self_test_passed: false,
            message: Some(PROCESS_TREE_CONFINEMENT_UNCERTIFIED.into()),
        }),
        error_code: Some(ErrorCode::BackendUnavailable),
        error_message: Some(PROCESS_TREE_CONFINEMENT_UNCERTIFIED.into()),
    }
}

pub fn build_seatbelt_profile(input: &SeatbeltProfileInput) -> Result<String, (ErrorCode, String)> {
    if !matches!(&input.network_mode, NetworkMode::Off) {
        return Err((
            ErrorCode::NetworkDenied,
            "macOS Seatbelt has no approved proxy route; network access is denied".into(),
        ));
    }
    validate_profile_paths(input)?;

    let mut profile = String::from(
        "(version 1)\n\
         (deny default)\n\
         (allow process-exec)\n\
         (allow process-fork)\n\
         (allow signal (target same-sandbox))\n\
         (allow process-info* (target same-sandbox))\n\
         (allow sysctl-read)\n\
         (allow file-read* (literal \"/dev/null\"))\n\
         (allow file-write* (literal \"/dev/null\"))\n\
         (deny network*)\n",
    );
    for root in SYSTEM_READ_ROOTS {
        profile.push_str(&format!(
            "(allow file-read* (subpath {}))\n",
            quote_sbpl(root)
        ));
    }
    profile.push_str("(allow file-read* (literal \"/private/etc/hosts\"))\n");
    profile.push_str("(allow file-read* (literal \"/private/etc/services\"))\n");

    for root in &input.readable_roots {
        profile.push_str(&format!(
            "(allow file-read* (subpath {}))\n",
            quote_sbpl_path(root)
        ));
    }
    for path in &input.protected_paths {
        profile.push_str(&format!(
            "(allow file-read* (subpath {}))\n",
            quote_sbpl_path(path)
        ));
    }
    if !matches!(&input.mode, SandboxMode::ReadOnly) {
        for root in &input.writable_roots {
            profile.push_str(&format!(
                "(allow file-write* (subpath {}))\n",
                quote_sbpl_path(root)
            ));
        }
    }
    // Seatbelt deny rules override the broader writable-root grants above.
    for path in &input.protected_paths {
        profile.push_str(&format!(
            "(deny file-write* (subpath {}))\n",
            quote_sbpl_path(path)
        ));
    }
    Ok(profile)
}

pub fn quote_sbpl(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len() + 2);
    escaped.push('"');
    for character in value.chars() {
        match character {
            '"' => escaped.push_str("\\\""),
            '\\' => escaped.push_str("\\\\"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            character if character.is_control() => {
                escaped.push_str(&format!("\\x{:02x}", character as u32));
            }
            character => escaped.push(character),
        }
    }
    escaped.push('"');
    escaped
}

pub fn is_minimal_environment_key(key: &str) -> bool {
    matches!(key, "LANG" | "LC_ALL" | "LC_CTYPE" | "TERM" | "TZ")
}

fn resolve_execution(request: ExecutionRequest) -> Result<ResolvedExecution, (ErrorCode, String)> {
    if !matches!(&request.profile.network_mode, NetworkMode::Off) {
        return Err((
            ErrorCode::NetworkDenied,
            "network access is unavailable until Promethe's managed proxy is enabled".into(),
        ));
    }
    if request.executable.is_empty() || request.executable.contains('\0') {
        return Err((
            ErrorCode::InvalidRequest,
            "executable is empty or invalid".into(),
        ));
    }
    if request
        .arguments
        .iter()
        .any(|argument| argument.contains('\0'))
    {
        return Err((
            ErrorCode::InvalidRequest,
            "arguments may not contain NUL bytes".into(),
        ));
    }
    crate::command_policy::validate_direct_command(&request.executable, &request.arguments)
        .map_err(|message| (ErrorCode::PolicyDenied, message))?;
    let working_directory =
        resolve_existing_absolute_path(&request.working_directory, "working directory")?;
    let readable_roots = resolve_roots(&request.profile.readable_roots, "readable root")?;
    let writable_roots = resolve_roots(&request.profile.writable_roots, "writable root")?;
    let protected_paths =
        resolve_protected_paths(&request.profile.protected_paths, readable_roots.first())?;
    if !readable_roots
        .iter()
        .any(|root| is_within(&working_directory, root))
    {
        return Err((
            ErrorCode::WorkspaceViolation,
            "working directory is outside of readable roots".into(),
        ));
    }
    if matches!(&request.profile.mode, SandboxMode::ReadOnly) && !writable_roots.is_empty() {
        return Err((
            ErrorCode::PolicyDenied,
            "read-only profiles cannot declare writable roots".into(),
        ));
    }
    if writable_roots.iter().any(|root| {
        !readable_roots
            .iter()
            .any(|readable| is_within(root, readable))
    }) {
        return Err((
            ErrorCode::WorkspaceViolation,
            "writable roots must be contained by readable roots".into(),
        ));
    }
    let profile = SeatbeltProfileInput {
        mode: request.profile.mode.clone(),
        network_mode: request.profile.network_mode.clone(),
        readable_roots,
        writable_roots,
        protected_paths,
    };
    validate_profile_paths(&profile)?;
    let environment = resolve_environment(&request)?;
    Ok(ResolvedExecution {
        executable: request.executable.clone(),
        arguments: request.arguments.clone(),
        working_directory,
        environment,
        profile,
        request,
    })
}

fn resolve_environment(
    request: &ExecutionRequest,
) -> Result<Vec<(String, String)>, (ErrorCode, String)> {
    let mut environment = Vec::new();
    for (key, value) in &request.environment {
        if request.sensitive_environment_keys.contains(key) || looks_sensitive(key) {
            return Err((
                ErrorCode::PolicyDenied,
                format!("sensitive environment variable {key} is forbidden in the sandbox"),
            ));
        }
        if !is_minimal_environment_key(key) {
            return Err((
                ErrorCode::PolicyDenied,
                format!("environment variable {key} is not part of the sandbox allow-list"),
            ));
        }
        if key.contains('\0') || value.contains('\0') {
            return Err((
                ErrorCode::InvalidRequest,
                "environment contains a NUL byte".into(),
            ));
        }
        environment.push((key.clone(), value.clone()));
    }
    Ok(environment)
}

fn looks_sensitive(key: &str) -> bool {
    let normalized = key.to_ascii_uppercase();
    ["TOKEN", "SECRET", "PASSWORD", "API_KEY", "CREDENTIAL"]
        .iter()
        .any(|needle| normalized.contains(needle))
}

fn resolve_roots(paths: &[String], label: &str) -> Result<Vec<PathBuf>, (ErrorCode, String)> {
    paths
        .iter()
        .map(|path| resolve_existing_absolute_path(path, label))
        .collect()
}

fn resolve_protected_paths(
    paths: &[String],
    workspace_root: Option<&PathBuf>,
) -> Result<Vec<PathBuf>, (ErrorCode, String)> {
    paths
        .iter()
        .map(|value| {
            let path = Path::new(value);
            if path.is_absolute() {
                resolve_existing_or_lexical_path(path, "protected path")
            } else {
                let root = workspace_root.ok_or_else(|| {
                    (
                        ErrorCode::WorkspaceViolation,
                        "relative protected paths require a readable workspace root".into(),
                    )
                })?;
                resolve_existing_or_lexical_path(&root.join(path), "protected path")
            }
        })
        .collect()
}

fn resolve_existing_or_lexical_path(
    path: &Path,
    label: &str,
) -> Result<PathBuf, (ErrorCode, String)> {
    if path.to_string_lossy().contains('\0') || !path.is_absolute() {
        return Err((
            ErrorCode::WorkspaceViolation,
            format!("{label} must be an absolute path without NUL bytes"),
        ));
    }
    if path.exists() {
        fs::canonicalize(path).map_err(|error| {
            (
                ErrorCode::WorkspaceViolation,
                format!("{label} must resolve safely: {error}"),
            )
        })
    } else {
        Ok(path.to_path_buf())
    }
}

fn resolve_existing_absolute_path(
    value: &str,
    label: &str,
) -> Result<PathBuf, (ErrorCode, String)> {
    if value.contains('\0') {
        return Err((
            ErrorCode::InvalidRequest,
            format!("{label} contains a NUL byte"),
        ));
    }
    let path = Path::new(value);
    if !path.is_absolute() {
        return Err((
            ErrorCode::WorkspaceViolation,
            format!("{label} must be an absolute path"),
        ));
    }
    fs::canonicalize(path).map_err(|error| {
        (
            ErrorCode::WorkspaceViolation,
            format!("{label} must exist and resolve safely: {error}"),
        )
    })
}

fn validate_profile_paths(input: &SeatbeltProfileInput) -> Result<(), (ErrorCode, String)> {
    for path in input
        .readable_roots
        .iter()
        .chain(input.writable_roots.iter())
        .chain(input.protected_paths.iter())
    {
        if !path.is_absolute() || path.as_os_str().is_empty() {
            return Err((
                ErrorCode::WorkspaceViolation,
                "Seatbelt profile paths must be absolute".into(),
            ));
        }
        if path.to_string_lossy().contains('\0') {
            return Err((
                ErrorCode::InvalidRequest,
                "Seatbelt profile path contains NUL".into(),
            ));
        }
    }
    Ok(())
}

fn quote_sbpl_path(path: &Path) -> String {
    quote_sbpl(&path.to_string_lossy())
}

fn is_within(path: &Path, root: &Path) -> bool {
    path.starts_with(root)
}

fn seatbelt_available() -> bool {
    fs::metadata(SEATBELT_EXECUTABLE)
        .map(|metadata| metadata.is_file() && is_executable(&metadata))
        .unwrap_or(false)
}

fn is_executable(metadata: &fs::Metadata) -> bool {
    use std::os::unix::fs::PermissionsExt;

    metadata.permissions().mode() & 0o111 != 0
}

#[allow(dead_code)]
fn seatbelt_self_test() -> Result<(), String> {
    if !seatbelt_available() {
        return Err("/usr/bin/sandbox-exec is unavailable".into());
    }
    let profile = SeatbeltProfileInput {
        mode: SandboxMode::ReadOnly,
        network_mode: NetworkMode::Off,
        readable_roots: Vec::new(),
        writable_roots: Vec::new(),
        protected_paths: Vec::new(),
    };
    let profile = build_seatbelt_profile(&profile).map_err(|(_, message)| message)?;
    let status = Command::new(SEATBELT_EXECUTABLE)
        .arg("-p")
        .arg(profile)
        .arg("--")
        .arg("/usr/bin/true")
        .env_clear()
        .env("PATH", MINIMAL_PATH)
        .status()
        .map_err(|error| format!("failed to execute macOS Seatbelt self-test: {error}"))?;
    if status.success() {
        Ok(())
    } else {
        Err(format!("macOS Seatbelt self-test exited with {status}"))
    }
}

fn apply_resource_limits(limits: &crate::protocol::ResourceLimits) -> io::Result<()> {
    set_limit(
        libc::RLIMIT_CPU,
        ((limits.timeout_millis.saturating_add(999)) / 1000).max(1),
    )?;
    set_limit(libc::RLIMIT_AS, limits.memory_bytes)?;
    set_limit(libc::RLIMIT_NPROC, u64::from(limits.process_limit.max(1)))?;
    Ok(())
}

fn set_limit(resource: libc::c_int, value: u64) -> io::Result<()> {
    let limit = libc::rlimit {
        rlim_cur: value as libc::rlim_t,
        rlim_max: value as libc::rlim_t,
    };
    if unsafe { libc::setrlimit(resource, &limit) } != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(())
}

fn wait_with_timeout(
    child: &mut Child,
    process_group: i32,
    timeout_millis: u64,
) -> (Option<std::process::ExitStatus>, bool) {
    let deadline = Instant::now() + Duration::from_millis(timeout_millis.max(1));
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return (Some(status), false),
            Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(10)),
            Ok(None) => {
                terminate_process_group(process_group);
                return (child.wait().ok(), true);
            }
            Err(_) => {
                terminate_process_group(process_group);
                return (child.wait().ok(), false);
            }
        }
    }
}

fn terminate_process_group(process_group: i32) {
    // A negative PID targets the child-created process group rather than the
    // Promethe gateway's own group.
    unsafe {
        libc::kill(-process_group, libc::SIGKILL);
    }
}

fn wait_for_process_group_exit(process_group: i32, timeout: Duration) {
    let deadline = Instant::now() + timeout;
    while process_group_exists(process_group) && Instant::now() < deadline {
        thread::sleep(Duration::from_millis(10));
    }
}

fn process_group_exists(process_group: i32) -> bool {
    if process_group <= 0 {
        return false;
    }
    let result = unsafe { libc::kill(-process_group, 0) };
    result == 0 || io::Error::last_os_error().raw_os_error() == Some(libc::EPERM)
}

struct CapturedOutput {
    text: String,
    truncated: bool,
}

fn read_bounded<R: Read + Send + 'static>(
    mut stream: R,
    max_bytes: usize,
) -> thread::JoinHandle<io::Result<CapturedOutput>> {
    thread::spawn(move || {
        let mut buffer = [0_u8; 8192];
        let mut captured = Vec::with_capacity(max_bytes.min(8192));
        let mut truncated = false;
        loop {
            let count = stream.read(&mut buffer)?;
            if count == 0 {
                break;
            }
            let remaining = max_bytes.saturating_sub(captured.len());
            let accepted = remaining.min(count);
            captured.extend_from_slice(&buffer[..accepted]);
            truncated |= accepted < count;
        }
        Ok(CapturedOutput {
            text: String::from_utf8_lossy(&captured).into_owned(),
            truncated,
        })
    })
}

fn collect_output(
    handle: Option<thread::JoinHandle<io::Result<CapturedOutput>>>,
) -> CapturedOutput {
    handle
        .and_then(|handle| handle.join().ok())
        .and_then(Result::ok)
        .unwrap_or_else(|| CapturedOutput {
            text: String::new(),
            truncated: false,
        })
}

fn execution_error(operation: IpcOperation, code: ErrorCode, message: &str) -> IpcResponse {
    IpcResponse {
        protocol_version: PROTOCOL_VERSION,
        operation,
        execution: None,
        status: None,
        error_code: Some(code),
        error_message: Some(message.into()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::protocol::{ApprovalPolicy, PermissionProfile, ResourceLimits};
    use std::collections::{BTreeMap, BTreeSet};
    use std::os::unix::process::CommandExt;

    fn profile(mode: SandboxMode, network_mode: NetworkMode) -> SeatbeltProfileInput {
        SeatbeltProfileInput {
            mode,
            network_mode,
            readable_roots: vec![PathBuf::from("/workspace")],
            writable_roots: vec![PathBuf::from("/workspace")],
            protected_paths: vec![PathBuf::from("/workspace/.git")],
        }
    }

    #[test]
    fn quotes_profile_literals_without_rule_injection() {
        assert_eq!(
            quote_sbpl("/work/\"evil\"\\path\n"),
            "\"/work/\\\"evil\\\"\\\\path\\n\""
        );
    }

    #[test]
    fn generated_profile_protects_metadata_after_workspace_write() {
        let generated =
            build_seatbelt_profile(&profile(SandboxMode::WorkspaceWrite, NetworkMode::Off))
                .expect("profile should be valid");
        let writable = generated
            .find("(allow file-write* (subpath \"/workspace\"))")
            .unwrap();
        let protected = generated
            .find("(deny file-write* (subpath \"/workspace/.git\"))")
            .unwrap();
        assert!(writable < protected);
        assert!(generated.contains("(deny network*)"));
    }

    #[test]
    fn full_access_keeps_network_closed() {
        let generated = build_seatbelt_profile(&profile(SandboxMode::FullAccess, NetworkMode::Off))
            .expect("full access must still use a closed network policy");
        assert!(generated.contains("(deny network*)"));
        assert!(
            build_seatbelt_profile(&profile(SandboxMode::FullAccess, NetworkMode::Allowlist))
                .is_err()
        );
    }

    #[test]
    fn environment_allow_list_rejects_secrets_and_loader_injection() {
        assert!(is_minimal_environment_key("LANG"));
        assert!(!is_minimal_environment_key("DYLD_INSERT_LIBRARIES"));
        assert!(looks_sensitive("OPENAI_API_KEY"));
        assert!(looks_sensitive("session_token"));
    }

    #[test]
    fn backend_stays_fail_closed_until_process_tree_confinement_is_certified() {
        for response in [status(), self_test()] {
            let state = response.status.expect("status response");
            assert!(!state.available);
            assert!(!state.self_test_passed);
            assert!(matches!(
                response.error_code,
                Some(ErrorCode::BackendUnavailable)
            ));
        }

        let response = execute(IpcRequest {
            protocol_version: PROTOCOL_VERSION,
            operation: IpcOperation::Execute,
            execution: None,
            execution_id: None,
        });
        let result = response.execution.expect("execution response");
        assert!(matches!(
            result.error_code,
            Some(ErrorCode::BackendUnavailable)
        ));
    }

    #[test]
    fn normal_parent_exit_still_terminates_process_group_descendants() {
        let mut command = Command::new("/bin/sh");
        command.arg("-c").arg("sleep 30 & exit 0");
        unsafe {
            command.pre_exec(|| {
                if libc::setpgid(0, 0) != 0 {
                    return Err(io::Error::last_os_error());
                }
                Ok(())
            });
        }
        let mut child = command.spawn().expect("spawn process group");
        let process_group = child.id() as i32;
        let (status, timed_out) = wait_with_timeout(&mut child, process_group, 1_000);
        assert!(status.is_some());
        assert!(!timed_out);
        assert!(process_group_exists(process_group));

        terminate_process_group(process_group);
        wait_for_process_group_exit(process_group, Duration::from_secs(1));
        assert!(!process_group_exists(process_group));
    }

    #[test]
    fn cancel_is_reported_independently_from_process_exit_status() {
        let execution_id = "cancel-test";
        cancelled_executions()
            .lock()
            .unwrap()
            .insert(execution_id.into());
        assert!(cancelled_executions().lock().unwrap().remove(execution_id));
    }

    #[test]
    fn shell_policy_is_applied_before_seatbelt_execution() {
        let temporary = std::env::temp_dir();
        let request = ExecutionRequest {
            protocol_version: PROTOCOL_VERSION,
            execution_id: "exec-1".into(),
            session_id: "session-1".into(),
            executable: "/bin/sh".into(),
            arguments: vec!["-c".into(), "id".into()],
            working_directory: temporary.to_string_lossy().into_owned(),
            environment: BTreeMap::new(),
            sensitive_environment_keys: BTreeSet::new(),
            profile: PermissionProfile {
                mode: SandboxMode::ReadOnly,
                approval_policy: ApprovalPolicy::OnRequest,
                network_mode: NetworkMode::Off,
                readable_roots: vec![temporary.to_string_lossy().into_owned()],
                writable_roots: Vec::new(),
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
        };
        assert!(matches!(
            resolve_execution(request),
            Err((ErrorCode::PolicyDenied, _))
        ));
    }
}
