//! Small Android adapter for the same C ABI used by iOS.
use crate::{ts_capture, ts_command, ts_create, ts_destroy, ts_free, ts_playback, ts_poll, Bridge};
use jni::{
    errors::{Error, JniError, ThrowRuntimeExAndDefault},
    objects::{JByteArray, JFloatArray, JObject, JShortArray},
    sys::{jbyteArray, jint, jlong},
    EnvUnowned,
};
use std::ffi::{CStr, CString};

fn invalid() -> Error {
    Error::JniCall(JniError::InvalidArguments)
}

#[no_mangle]
pub extern "system" fn Java_dev_mobilespeak_mobilespeak_NativeCore_create<'a>(
    mut env: EnvUnowned<'a>,
    _object: JObject<'a>,
) -> jlong {
    env.with_env(|_| -> jni::errors::Result<_> { Ok(ts_create() as jlong) })
        .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_mobilespeak_mobilespeak_NativeCore_destroy<'a>(
    mut env: EnvUnowned<'a>,
    _object: JObject<'a>,
    handle: jlong,
) {
    env.with_env(|_| -> jni::errors::Result<_> {
        unsafe { ts_destroy(handle as *mut Bridge) };
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_mobilespeak_mobilespeak_NativeCore_command<'a>(
    mut env: EnvUnowned<'a>,
    _object: JObject<'a>,
    handle: jlong,
    bytes: JByteArray<'a>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<_> {
        if handle == 0 {
            return Err(invalid());
        }
        let command = CString::new(env.convert_byte_array(&bytes)?).map_err(|_| invalid())?;
        Ok(unsafe { ts_command(handle as *mut Bridge, command.as_ptr()) })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_mobilespeak_mobilespeak_NativeCore_poll<'a>(
    mut env: EnvUnowned<'a>,
    _object: JObject<'a>,
    handle: jlong,
) -> jbyteArray {
    env.with_env(|env| -> jni::errors::Result<_> {
        if handle == 0 {
            return Err(invalid());
        }
        let pointer = unsafe { ts_poll(handle as *mut Bridge) };
        if pointer.is_null() {
            return Err(invalid());
        }
        let bytes = unsafe { CStr::from_ptr(pointer).to_bytes().to_vec() };
        unsafe { ts_free(pointer) };
        Ok(env.byte_array_from_slice(&bytes)?.into_raw())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_mobilespeak_mobilespeak_NativeCore_capture<'a>(
    mut env: EnvUnowned<'a>,
    _object: JObject<'a>,
    handle: jlong,
    samples: JShortArray<'a>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<_> {
        if handle == 0 || samples.len(env)? != 960 {
            return Err(invalid());
        }
        let mut pcm = [0i16; 960];
        samples.get_region(env, 0, &mut pcm)?;
        Ok(unsafe { ts_capture(handle as *mut Bridge, pcm.as_ptr(), pcm.len()) })
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_mobilespeak_mobilespeak_NativeCore_playback<'a>(
    mut env: EnvUnowned<'a>,
    _object: JObject<'a>,
    handle: jlong,
    samples: JFloatArray<'a>,
) -> jint {
    env.with_env(|env| -> jni::errors::Result<_> {
        if handle == 0 || samples.len(env)? < 1920 {
            return Err(invalid());
        }
        let mut pcm = [0f32; 1920];
        let count = unsafe { ts_playback(handle as *mut Bridge, pcm.as_mut_ptr(), pcm.len()) };
        if count > 0 {
            samples.set_region(env, 0, &pcm[..count])?;
        }
        Ok(count as jint)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}
