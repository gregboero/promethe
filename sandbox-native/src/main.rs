mod command_policy;
mod platform;
mod protocol;

use std::io::{self, BufRead, Write};
use std::sync::{Arc, Mutex};
use std::thread;

use protocol::{IpcOperation, IpcRequest, IpcResponse, PROTOCOL_VERSION};

const MAX_ACTIVE_EXECUTIONS: usize = 8;
const MAX_REQUEST_LINE_BYTES: usize = 2 * 1024 * 1024;

enum InputFrame {
    EndOfInput,
    Line(Vec<u8>),
    TooLarge,
}

fn main() {
    let stdin = io::stdin();
    let stdout = Arc::new(Mutex::new(io::stdout()));
    let mut workers: Vec<thread::JoinHandle<()>> = Vec::new();
    let mut input = stdin.lock();

    loop {
        workers.retain(|worker| !worker.is_finished());
        let request = match read_bounded_line(&mut input, MAX_REQUEST_LINE_BYTES) {
            Ok(InputFrame::EndOfInput) => break,
            Ok(InputFrame::TooLarge) => {
                let response = IpcResponse::protocol_error(format!(
                    "request exceeds the {MAX_REQUEST_LINE_BYTES}-byte JSONL limit"
                ));
                if !write_response(&stdout, &response) {
                    break;
                }
                continue;
            }
            Ok(InputFrame::Line(line)) => match parse_request(&line) {
                Ok(request) => request,
                Err(response) => {
                    if !write_response(&stdout, &response) {
                        break;
                    }
                    continue;
                }
            },
            Err(error) => {
                let response =
                    IpcResponse::protocol_error(format!("failed to read request: {error}"));
                let _ = write_response(&stdout, &response);
                break;
            }
        };

        if request.operation == IpcOperation::Execute {
            if workers.len() >= MAX_ACTIVE_EXECUTIONS {
                let execution_id = request
                    .execution
                    .as_ref()
                    .map(|execution| execution.execution_id.clone())
                    .unwrap_or_default();
                let response = IpcResponse::execution_error(
                    execution_id,
                    protocol::ErrorCode::ResourceLimit,
                    "sandbox execution concurrency limit reached".into(),
                );
                if !write_response(&stdout, &response) {
                    break;
                }
                continue;
            }
            let worker_stdout = Arc::clone(&stdout);
            workers.push(thread::spawn(move || {
                let response = platform::execute(request);
                let _ = write_response(&worker_stdout, &response);
            }));
            continue;
        }

        let response = dispatch_control(request);
        if !write_response(&stdout, &response) {
            break;
        }
    }

    for worker in workers {
        let _ = worker.join();
    }
}

fn read_bounded_line<R: BufRead>(reader: &mut R, max_bytes: usize) -> io::Result<InputFrame> {
    let mut line = Vec::with_capacity(max_bytes.min(8 * 1024));
    let mut too_large = false;
    let mut read_any = false;

    loop {
        let available = reader.fill_buf()?;
        if available.is_empty() {
            if !read_any {
                return Ok(InputFrame::EndOfInput);
            }
            break;
        }
        read_any = true;

        let newline = available.iter().position(|byte| *byte == b'\n');
        let payload_length = newline.unwrap_or(available.len());
        if !too_large {
            if line.len().saturating_add(payload_length) > max_bytes {
                too_large = true;
            } else {
                line.extend_from_slice(&available[..payload_length]);
            }
        }

        let consumed = payload_length + usize::from(newline.is_some());
        reader.consume(consumed);
        if newline.is_some() {
            break;
        }
    }

    if too_large {
        return Ok(InputFrame::TooLarge);
    }
    if line.last() == Some(&b'\r') {
        line.pop();
    }
    Ok(InputFrame::Line(line))
}

fn parse_request(line: &[u8]) -> Result<IpcRequest, Box<IpcResponse>> {
    let request: IpcRequest = match serde_json::from_slice(line) {
        Ok(request) => request,
        Err(error) => {
            return Err(Box::new(IpcResponse::protocol_error(format!(
                "invalid JSON request: {error}"
            ))))
        }
    };
    if request.protocol_version != PROTOCOL_VERSION {
        return Err(Box::new(IpcResponse::protocol_error(format!(
            "unsupported protocol version {}",
            request.protocol_version
        ))));
    }
    Ok(request)
}

fn dispatch_control(request: IpcRequest) -> IpcResponse {
    match request.operation {
        IpcOperation::Execute => IpcResponse::protocol_error("invalid control operation".into()),
        IpcOperation::Cancel => platform::cancel(request),
        IpcOperation::Status => platform::status(),
        IpcOperation::SelfTest => platform::self_test(),
    }
}

fn write_response(stdout: &Arc<Mutex<io::Stdout>>, response: &IpcResponse) -> bool {
    let Ok(mut writer) = stdout.lock() else {
        return false;
    };
    serde_json::to_writer(&mut *writer, response).is_ok()
        && writeln!(&mut *writer).is_ok()
        && writer.flush().is_ok()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn bounded_reader_accepts_limit_and_strips_crlf() {
        let mut input = Cursor::new(b"1234\r\n".to_vec());
        match read_bounded_line(&mut input, 5).unwrap() {
            InputFrame::Line(line) => assert_eq!(line, b"1234"),
            _ => panic!("expected a bounded line"),
        }
    }

    #[test]
    fn oversized_line_is_drained_before_next_frame() {
        let mut input = Cursor::new(b"123456\n{}\n".to_vec());
        assert!(matches!(
            read_bounded_line(&mut input, 5).unwrap(),
            InputFrame::TooLarge
        ));
        match read_bounded_line(&mut input, 5).unwrap() {
            InputFrame::Line(line) => assert_eq!(line, b"{}"),
            _ => panic!("expected the frame after the oversized line"),
        }
    }

    #[test]
    fn reader_returns_unterminated_final_frame_then_eof() {
        let mut input = Cursor::new(b"{}".to_vec());
        assert!(matches!(
            read_bounded_line(&mut input, 5).unwrap(),
            InputFrame::Line(line) if line == b"{}"
        ));
        assert!(matches!(
            read_bounded_line(&mut input, 5).unwrap(),
            InputFrame::EndOfInput
        ));
    }
}
