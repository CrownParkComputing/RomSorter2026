use indicatif::{ProgressBar, ProgressStyle};
use lazy_static::lazy_static;
use std::sync::Arc;
use std::sync::Mutex;

lazy_static! {
    static ref LOG_BUFFER: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
}

#[cfg(target_os = "android")]
fn redirect_stdout_to_log() {
    use std::os::fd::FromRawFd;

    struct LogWriter;

    impl std::io::Write for LogWriter {
        fn write(&mut self, buf: &[u8]) -> std::io::Result<usize> {
            if !buf.is_empty() {
                let text = String::from_utf8_lossy(buf);
                let lines = text.split('\n');
                for line in lines {
                    let trimmed = line.trim_end();
                    if !trimmed.is_empty() {
                        let mut buffer = LOG_BUFFER.lock().unwrap();
                        buffer.push(trimmed.to_string());
                        if buffer.len() > 1000 {
                            buffer.remove(0);
                        }
                    }
                }
            }
            Ok(buf.len())
        }

        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    // Create a pipe: fd[0]=read end, fd[1]=write end
    let mut fds: [libc::c_int; 2] = [-1, -1];
    unsafe {
        if libc::pipe(fds.as_mut_ptr()) == 0 {
            // Redirect stdout to the write end of the pipe
            libc::dup2(fds[1], libc::STDOUT_FILENO);
            libc::dup2(fds[1], libc::STDERR_FILENO);
            libc::close(fds[1]);
            // Force line-buffering on the pipe so Rust flushes after every newline.
            // Use libc stdio handles to set buffering mode.
            let stdout_ptr = libc::fdopen(libc::STDOUT_FILENO, b"w\0".as_ptr() as *const libc::c_char);
            if !stdout_ptr.is_null() {
                libc::setvbuf(stdout_ptr, std::ptr::null_mut(), libc::_IONBF, 0);
            }
            let stderr_ptr = libc::fdopen(libc::STDERR_FILENO, b"w\0".as_ptr() as *const libc::c_char);
            if !stderr_ptr.is_null() {
                libc::setvbuf(stderr_ptr, std::ptr::null_mut(), libc::_IONBF, 0);
            }
            // Spawn a thread that reads from the pipe and writes to LOG_BUFFER
            let read_fd = fds[0];
            std::thread::spawn(move || {
                let mut reader = std::io::BufReader::new(std::fs::File::from_raw_fd(read_fd));
                let mut line = String::new();
                loop {
                    line.clear();
                    match std::io::BufRead::read_line(&mut reader, &mut line) {
                        Ok(0) => break, // EOF
                        Ok(_) => {
                            let trimmed = line.trim_end();
                            if !trimmed.is_empty() {
                                let mut buffer = LOG_BUFFER.lock().unwrap();
                                buffer.push(trimmed.to_string());
                                if buffer.len() > 1000 {
                                    buffer.remove(0);
                                }
                            }
                        }
                        Err(_) => break,
                    }
                }
            });
        }
    }
}

pub fn log(msg: &str) {
    #[cfg(target_os = "android")]
    {
        let mut buffer = LOG_BUFFER.lock().unwrap();
        buffer.push(msg.to_string());
        if buffer.len() > 1000 {
            buffer.remove(0);
        }
    }
    println!("{}", msg);
    let _ = std::io::Write::flush(&mut std::io::stdout());
}

#[cfg(target_os = "android")]
pub fn init_android_logging() {
    static INIT: std::sync::Once = std::sync::Once::new();
    INIT.call_once(|| {
        redirect_stdout_to_log();
    });
}

pub fn get_logs() -> String {
    let mut buffer = LOG_BUFFER.lock().unwrap();
    let logs = buffer.join("\n");
    buffer.clear();
    logs
}

pub fn file_progress(size: u64, message: &str) -> ProgressBar {
    let pb = ProgressBar::new(size);
    pb.set_style(
        ProgressStyle::default_bar()
            .template("{spinner:.green} [{elapsed_precise}] [{bar:80.cyan/blue}] {bytes}/{total_bytes} ({eta})\n  {msg}")
            .unwrap()
            .progress_chars("#>-"),
    );
    pb.set_message(message.to_string());
    pb
}

pub fn folder_progress(count: u64, message: &str) -> ProgressBar {
    let pb = ProgressBar::new(count);
    pb.set_style(
        ProgressStyle::default_bar()
            .template("{spinner:.green} [{elapsed_precise}] [{bar:80.cyan/blue}] {pos}/{len} ({eta})\n  {msg}")
            .unwrap()
            .progress_chars("#>-"),
    );
    pb.set_message(message.to_string());
    pb
}
