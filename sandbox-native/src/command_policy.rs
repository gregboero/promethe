pub(crate) fn validate_direct_command(
    executable: &str,
    arguments: &[String],
) -> Result<(), String> {
    let executable_name = command_name(executable);
    if is_shell_interpreter(executable_name) || is_shell_script(executable_name) {
        return Err(format!(
            "shell interpreters and shell scripts are forbidden: {executable_name}"
        ));
    }

    if arguments.iter().any(|argument| {
        argument.contains('\n')
            || argument.contains('\r')
            || is_shell_control_argument(argument.trim())
    }) {
        return Err("pipe, redirection, and command-control arguments are forbidden".into());
    }

    for (index, argument) in arguments.iter().enumerate() {
        let name = command_name(argument);
        if is_shell_interpreter(name)
            && arguments
                .get(index + 1)
                .is_some_and(|flag| is_shell_evaluation_flag(name, flag))
        {
            return Err(format!(
                "indirect shell evaluation is forbidden: {name} {}",
                arguments[index + 1]
            ));
        }
    }

    Ok(())
}

fn command_name(value: &str) -> &str {
    value.rsplit(['/', '\\']).next().unwrap_or(value).trim()
}

fn is_shell_interpreter(value: &str) -> bool {
    let normalized = value.to_ascii_lowercase();
    matches!(
        normalized.strip_suffix(".exe").unwrap_or(&normalized),
        "sh" | "bash" | "zsh" | "fish" | "cmd" | "powershell" | "pwsh"
    )
}

fn is_shell_script(value: &str) -> bool {
    let normalized = value.to_ascii_lowercase();
    [".sh", ".bash", ".zsh", ".fish", ".cmd", ".bat", ".ps1"]
        .iter()
        .any(|suffix| normalized.ends_with(suffix))
}

fn is_shell_evaluation_flag(shell: &str, flag: &str) -> bool {
    let normalized_shell = shell.to_ascii_lowercase();
    let shell = normalized_shell
        .strip_suffix(".exe")
        .unwrap_or(&normalized_shell);
    let flag = flag.to_ascii_lowercase();
    match shell {
        "cmd" => matches!(flag.as_str(), "/c" | "/k"),
        "powershell" | "pwsh" => matches!(
            flag.as_str(),
            "-c" | "-command" | "-encodedcommand" | "-enc"
        ),
        _ => matches!(flag.as_str(), "-c" | "-lc"),
    }
}

fn is_shell_control_argument(value: &str) -> bool {
    if matches!(
        value,
        "|" | "||" | "&" | "&&" | ";" | ">" | ">>" | "<" | "<<" | "&>" | "&>>" | "2>&1" | "1>&2"
    ) {
        return true;
    }

    let without_fd = value.trim_start_matches(|character: char| character.is_ascii_digit());
    without_fd.starts_with('>') || without_fd.starts_with('<')
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_direct_shells_and_scripts_on_all_platforms() {
        for executable in [
            "sh",
            "/bin/bash",
            "C:\\Windows\\System32\\cmd.exe",
            "PowerShell.EXE",
            "pwsh",
            "./setup.ps1",
            "build.cmd",
        ] {
            assert!(
                validate_direct_command(executable, &[]).is_err(),
                "{executable} should be rejected"
            );
        }
    }

    #[test]
    fn rejects_indirect_shell_evaluation_and_control_arguments() {
        for arguments in [
            vec!["bash".into(), "-c".into(), "id".into()],
            vec!["cmd.exe".into(), "/C".into(), "whoami".into()],
            vec!["pwsh".into(), "-Command".into(), "Get-ChildItem".into()],
            vec!["output".into(), "|".into(), "more".into()],
            vec!["2>error.log".into()],
            vec!["line1\nline2".into()],
        ] {
            assert!(validate_direct_command("env", &arguments).is_err());
        }
    }

    #[test]
    fn accepts_structured_arguments_that_are_not_shell_syntax() {
        let arguments = vec![
            "status".into(),
            "--format=json".into(),
            "https://example.com/a?x=1&y=2".into(),
            "literal a > b".into(),
        ];
        assert!(validate_direct_command("git", &arguments).is_ok());
    }
}
