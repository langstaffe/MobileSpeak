//! Small Android adapter for the same C ABI used by iOS.
use crate::{ts_capture, ts_command, ts_destroy, ts_free, ts_poll, ts_set_notifier, Bridge};
use jni::{
    errors::{Error, JniError, ThrowRuntimeExAndDefault},
    jni_sig, jni_str,
    objects::{Global, JByteArray, JObject, JShortArray},
    sys::{jbyteArray, jint, jlong},
    EnvUnowned, JavaVM,
};
use std::ffi::{CStr, CString};
use std::{
    collections::HashMap,
    sync::{Mutex, OnceLock},
};

struct AndroidNotifier {
    callback: Global<JObject<'static>>,
}

static NOTIFIERS: OnceLock<Mutex<HashMap<usize, Box<AndroidNotifier>>>> = OnceLock::new();
// ndk-context borrows these pointers; keep the application global alive for the process.
static ANDROID_CONTEXT: Mutex<Option<Global<JObject<'static>>>> = Mutex::new(None);

fn notifiers() -> &'static Mutex<HashMap<usize, Box<AndroidNotifier>>> {
    NOTIFIERS.get_or_init(|| Mutex::new(HashMap::new()))
}

extern "C" fn android_notified(context: usize) {
    let notifier = unsafe { &*(context as *const AndroidNotifier) };
    let Ok(vm) = JavaVM::singleton() else { return };
    let _ = vm.attach_current_thread(|env| -> jni::errors::Result<()> {
        env.call_method(&notifier.callback, jni_str!("run"), jni_sig!("()V"), &[])?;
        Ok(())
    });
}

unsafe fn clear_notifier(handle: *mut Bridge) {
    ts_set_notifier(handle, None, 0);
    notifiers().lock().unwrap().remove(&(handle as usize));
}

fn invalid() -> Error {
    Error::JniCall(JniError::InvalidArguments)
}

#[no_mangle]
pub extern "system" fn Java_dev_mobilespeak_mobilespeak_NativeCore_create<'a>(
    mut env: EnvUnowned<'a>,
    _object: JObject<'a>,
    context: JObject<'a>,
) -> jlong {
    env.with_env(|env| -> jni::errors::Result<_> {
        if context.is_null() {
            return Err(invalid());
        }
        let vm = env.get_java_vm()?;
        let application = env
            .call_method(
                &context,
                jni_str!("getApplicationContext"),
                jni_sig!("()Landroid/content/Context;"),
                &[],
            )?
            .l()?;
        if application.is_null() {
            return Err(invalid());
        }
        {
            let mut saved = ANDROID_CONTEXT.lock().map_err(|_| invalid())?;
            if saved.is_none() {
                let global = env.new_global_ref(&application)?;
                let pointer = global.as_raw();
                *saved = Some(global);
                // The lock publishes initialization before any core worker can read the pointers.
                unsafe {
                    ndk_context::initialize_android_context(vm.get_raw().cast(), pointer.cast());
                }
            }
        }
        // Use the Rust constructor so creation panics can be handled by with_env, too.
        Ok(Box::into_raw(Box::new(Bridge::new())) as jlong)
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_mobilespeak_mobilespeak_NativeCore_destroy<'a>(
    mut env: EnvUnowned<'a>,
    _object: JObject<'a>,
    handle: jlong,
) {
    env.with_env(|_| -> jni::errors::Result<_> {
        let handle = handle as *mut Bridge;
        if !handle.is_null() {
            unsafe {
                clear_notifier(handle);
                ts_destroy(handle);
            }
        }
        Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>()
}

#[no_mangle]
pub extern "system" fn Java_dev_mobilespeak_mobilespeak_NativeCore_setNotifier<'a>(
    mut env: EnvUnowned<'a>,
    _object: JObject<'a>,
    handle: jlong,
    callback: JObject<'a>,
) {
    env.with_env(|env| -> jni::errors::Result<_> {
        let handle = handle as *mut Bridge;
        if handle.is_null() || callback.is_null() {
            return Err(invalid());
        }
        unsafe { clear_notifier(handle) };
        let mut notifier = Box::new(AndroidNotifier {
            callback: env.new_global_ref(&callback)?,
        });
        let context = notifier.as_mut() as *mut AndroidNotifier as usize;
        notifiers()
            .lock()
            .unwrap()
            .insert(handle as usize, notifier);
        unsafe { ts_set_notifier(handle, Some(android_notified), context) };
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
