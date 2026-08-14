// C ABI bridge over bridge_core, consumed by Flutter via dart:ffi on iOS
// (static lib, DynamicLibrary.process()) and Android (libnscb.so).
//
// Convention: every function returns a heap-allocated C string the caller
// must release with nscb_string_free. Errors are returned as "ERROR: ..."
// strings, matching the JNI bridge contract.

use std::ffi::{c_char, c_int, CStr, CString};

use crate::bridge_core;

fn cstr<'a>(ptr: *const c_char) -> Result<&'a str, String> {
    if ptr.is_null() {
        return Err("null pointer argument".to_string());
    }
    unsafe { CStr::from_ptr(ptr) }
        .to_str()
        .map_err(|e| format!("invalid UTF-8 argument: {e}"))
}

fn to_c_string(result: Result<String, String>) -> *mut c_char {
    let text = match result {
        Ok(msg) => msg,
        Err(err) => {
            crate::util::progress::log(&format!("ERROR: {err}"));
            format!("ERROR: {err}")
        }
    };
    // Interior NULs would truncate; replace them so we always return something.
    CString::new(text.replace('\0', " "))
        .map(CString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        unsafe {
            drop(CString::from_raw(ptr));
        }
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_configure_temp_root(temp_root: *const c_char) -> *mut c_char {
    to_c_string(cstr(temp_root).and_then(bridge_core::configure_temp_root))
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_merge(
    inputs_joined: *const c_char,
    output_path: *const c_char,
    keys_path: *const c_char,
    output_type: *const c_char,
) -> *mut c_char {
    to_c_string((|| {
        bridge_core::merge(
            cstr(inputs_joined)?,
            cstr(output_path)?,
            cstr(keys_path)?,
            cstr(output_type)?,
        )
    })())
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_compress(
    input_path: *const c_char,
    output_path: *const c_char,
    keys_path: *const c_char,
    level: c_int,
) -> *mut c_char {
    to_c_string((|| {
        bridge_core::compress(
            cstr(input_path)?,
            cstr(output_path)?,
            cstr(keys_path)?,
            level,
        )
    })())
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_decompress(
    input_path: *const c_char,
    output_path: *const c_char,
) -> *mut c_char {
    to_c_string((|| bridge_core::decompress(cstr(input_path)?, cstr(output_path)?))())
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_rename_path(
    path: *const c_char,
    keys_path: *const c_char,
    cache_dir: *const c_char,
    renmode: *const c_char,
    addlangue: *const c_char,
    noversion: *const c_char,
    dlcrname: *const c_char,
) -> *mut c_char {
    to_c_string((|| {
        bridge_core::rename_path(
            cstr(path)?,
            cstr(keys_path)?,
            cstr(cache_dir)?,
            cstr(renmode)?,
            cstr(addlangue)?,
            cstr(noversion)?,
            cstr(dlcrname)?,
        )
    })())
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_scan_directory(
    path: *const c_char,
    keys_path: *const c_char,
    cache_dir: *const c_char,
) -> *mut c_char {
    to_c_string((|| {
        bridge_core::scan_directory(cstr(path)?, cstr(keys_path)?, cstr(cache_dir)?)
    })())
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_scan_faulty_files(
    path: *const c_char,
    keys_path: *const c_char,
) -> *mut c_char {
    to_c_string((|| bridge_core::scan_faulty_files(cstr(path)?, cstr(keys_path)?))())
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_refresh_titledb(cache_dir: *const c_char) -> *mut c_char {
    to_c_string(cstr(cache_dir).and_then(bridge_core::refresh_titledb))
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_library_status(
    input_path: *const c_char,
    keys_path: *const c_char,
    cache_dir: *const c_char,
) -> *mut c_char {
    to_c_string((|| {
        bridge_core::library_status(cstr(input_path)?, cstr(keys_path)?, cstr(cache_dir)?)
    })())
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_get_logs() -> *mut c_char {
    to_c_string(Ok(bridge_core::get_logs()))
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_delete_file(path: *const c_char) -> *mut c_char {
    to_c_string(cstr(path).and_then(bridge_core::delete_file))
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_content_list(
    input_path: *const c_char,
    keys_path: *const c_char,
) -> *mut c_char {
    to_c_string((|| bridge_core::content_list(cstr(input_path)?, cstr(keys_path)?))())
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_file_list(
    input_path: *const c_char,
    keys_path: *const c_char,
) -> *mut c_char {
    to_c_string((|| bridge_core::file_list(cstr(input_path)?, cstr(keys_path)?))())
}

#[unsafe(no_mangle)]
pub extern "C" fn nscb_suggested_file_name(
    inputs_joined: *const c_char,
    keys_path: *const c_char,
    cache_dir: *const c_char,
    output_type: *const c_char,
) -> *mut c_char {
    to_c_string((|| {
        bridge_core::suggested_file_name(
            cstr(inputs_joined)?,
            cstr(keys_path)?,
            cstr(cache_dir)?,
            cstr(output_type)?,
        )
    })())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    fn take(ptr: *mut c_char) -> String {
        assert!(!ptr.is_null());
        let s = unsafe { CStr::from_ptr(ptr) }.to_string_lossy().into_owned();
        nscb_string_free(ptr);
        s
    }

    #[test]
    fn temp_root_roundtrip() {
        // Keep the directory alive: configure_temp_root points TMPDIR at it
        // process-wide, so deleting it would break unrelated parallel tests.
        let dir = tempfile::tempdir().unwrap().keep();
        let arg = CString::new(dir.to_str().unwrap()).unwrap();
        let out = take(nscb_configure_temp_root(arg.as_ptr()));
        assert!(out.starts_with("OK:"), "{out}");
    }

    #[test]
    fn null_argument_is_error_string() {
        let out = take(nscb_configure_temp_root(std::ptr::null()));
        assert!(out.starts_with("ERROR:"), "{out}");
    }

    #[test]
    fn missing_keys_is_error_string() {
        let input = CString::new("/nonexistent/dir").unwrap();
        let keys = CString::new("").unwrap();
        let cache = CString::new("/tmp").unwrap();
        let out = take(nscb_scan_directory(input.as_ptr(), keys.as_ptr(), cache.as_ptr()));
        assert!(out.starts_with("ERROR:"), "{out}");
        assert!(out.contains("prod.keys"), "{out}");
    }

    #[test]
    fn logs_are_returned() {
        crate::util::progress::log("ffi test marker");
        let out = take(nscb_get_logs());
        assert!(out.contains("ffi test marker"), "{out}");
    }
}
