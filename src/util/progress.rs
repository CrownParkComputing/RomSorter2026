use indicatif::{ProgressBar, ProgressStyle};
use lazy_static::lazy_static;
use std::sync::Arc;
use std::sync::Mutex;

lazy_static! {
    static ref LOG_BUFFER: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
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
