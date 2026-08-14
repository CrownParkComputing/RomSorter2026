// Thin JNI wrappers over bridge_core. All logic lives in bridge_core.rs so it
// is shared with the C FFI bridge (ffi_bridge.rs) used by the Flutter apps.

#![cfg(target_os = "android")]

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

use crate::bridge_core;
use crate::util::progress;

fn jstr_to_string(env: &mut JNIEnv<'_>, value: JString<'_>) -> Result<String, String> {
    env.get_string(&value)
        .map(|v| v.to_string_lossy().into_owned())
        .map_err(|e| format!("JNI string error: {e}"))
}

fn to_jstring(env: &mut JNIEnv<'_>, text: &str) -> jstring {
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn respond(env: &mut JNIEnv<'_>, result: Result<String, String>) -> jstring {
    match result {
        Ok(msg) => to_jstring(env, &msg),
        Err(err) => {
            progress::log(&format!("ERROR: {err}"));
            to_jstring(env, &format!("ERROR: {err}"))
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_configureTempRoot(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    temp_root: JString<'_>,
) -> jstring {
    let result = jstr_to_string(&mut env, temp_root)
        .and_then(|root| bridge_core::configure_temp_root(&root));
    respond(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_merge(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    inputs_joined: JString<'_>,
    output_path: JString<'_>,
    keys_path: JString<'_>,
    output_type: JString<'_>,
) -> jstring {
    let result = (|| {
        let inputs = jstr_to_string(&mut env, inputs_joined)?;
        let output = jstr_to_string(&mut env, output_path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let out_type = jstr_to_string(&mut env, output_type)?;
        bridge_core::merge(&inputs, &output, &keys, &out_type)
    })();
    respond(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_compress(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    output_path: JString<'_>,
    keys_path: JString<'_>,
    level: jni::sys::jint,
) -> jstring {
    let result = (|| {
        let input = jstr_to_string(&mut env, input_path)?;
        let output = jstr_to_string(&mut env, output_path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        bridge_core::compress(&input, &output, &keys, level as i32)
    })();
    respond(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_decompress(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    output_path: JString<'_>,
) -> jstring {
    let result = (|| {
        let input = jstr_to_string(&mut env, input_path)?;
        let output = jstr_to_string(&mut env, output_path)?;
        bridge_core::decompress(&input, &output)
    })();
    respond(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_renamePath(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
    keys_path: JString<'_>,
    cache_dir: JString<'_>,
    renmode: JString<'_>,
    addlangue: JString<'_>,
    noversion: JString<'_>,
    dlcrname: JString<'_>,
) -> jstring {
    let result = (|| {
        let p = jstr_to_string(&mut env, path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let cache = jstr_to_string(&mut env, cache_dir)?;
        let mode = jstr_to_string(&mut env, renmode)?;
        let lang = jstr_to_string(&mut env, addlangue)?;
        let nver = jstr_to_string(&mut env, noversion)?;
        let dlc = jstr_to_string(&mut env, dlcrname)?;
        bridge_core::rename_path(&p, &keys, &cache, &mode, &lang, &nver, &dlc)
    })();
    respond(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_scanDirectory(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
    keys_path: JString<'_>,
    cache_dir: JString<'_>,
) -> jstring {
    let result = (|| {
        let p = jstr_to_string(&mut env, path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let cache = jstr_to_string(&mut env, cache_dir)?;
        bridge_core::scan_directory(&p, &keys, &cache)
    })();
    respond(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_scanFaultyFiles(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
    keys_path: JString<'_>,
) -> jstring {
    let result = (|| {
        let p = jstr_to_string(&mut env, path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        bridge_core::scan_faulty_files(&p, &keys)
    })();
    respond(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_refreshTitleDb(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    cache_dir: JString<'_>,
) -> jstring {
    let result = jstr_to_string(&mut env, cache_dir)
        .and_then(|cache| bridge_core::refresh_titledb(&cache));
    respond(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_libraryStatus(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    keys_path: JString<'_>,
    cache_dir: JString<'_>,
) -> jstring {
    let result = (|| {
        let input = jstr_to_string(&mut env, input_path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let cache = jstr_to_string(&mut env, cache_dir)?;
        bridge_core::library_status(&input, &keys, &cache)
    })();
    respond(&mut env, result)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_getLogs(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    to_jstring(&mut env, &bridge_core::get_logs())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_deleteFile(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    path: JString<'_>,
) -> jstring {
    let result = jstr_to_string(&mut env, path).and_then(|p| bridge_core::delete_file(&p));
    // deleteFile historically did not log errors; keep the response shape only.
    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => to_jstring(&mut env, &format!("ERROR: {err}")),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_contentList(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    keys_path: JString<'_>,
) -> jstring {
    let result = (|| {
        let input = jstr_to_string(&mut env, input_path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        bridge_core::content_list(&input, &keys)
    })();
    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => to_jstring(&mut env, &format!("ERROR: {err}")),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_fileList(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    input_path: JString<'_>,
    keys_path: JString<'_>,
) -> jstring {
    let result = (|| {
        let input = jstr_to_string(&mut env, input_path)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        bridge_core::file_list(&input, &keys)
    })();
    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => to_jstring(&mut env, &format!("ERROR: {err}")),
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_nscb_android_NscbBridge_getSuggestedFileName(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    inputs_joined: JString<'_>,
    keys_path: JString<'_>,
    cache_dir: JString<'_>,
    output_type: JString<'_>,
) -> jstring {
    let result = (|| {
        let inputs = jstr_to_string(&mut env, inputs_joined)?;
        let keys = jstr_to_string(&mut env, keys_path)?;
        let cache = jstr_to_string(&mut env, cache_dir)?;
        let out_type = jstr_to_string(&mut env, output_type)?;
        bridge_core::suggested_file_name(&inputs, &keys, &cache, &out_type)
    })();
    match result {
        Ok(msg) => to_jstring(&mut env, &msg),
        Err(err) => to_jstring(&mut env, &format!("ERROR: {err}")),
    }
}
