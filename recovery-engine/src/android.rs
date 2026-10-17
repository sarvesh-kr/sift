//! JNI bridge. Retained read-only file descriptors; opaque IDs; bounded byte arrays.
use super::*;
use jni::{
    objects::{JClass, JObject, JString, JValue},
    sys::{jbyteArray, jint, jlong, jstring},
    JNIEnv,
};
use std::sync::{atomic::AtomicI64, Arc, Mutex, OnceLock};
struct Data {
    file: File,
    engine: Option<Undelete>,
}
struct Session {
    data: Mutex<Data>,
    cancel: AtomicBool,
}
static NEXT: AtomicI64 = AtomicI64::new(1);
static SESSIONS: OnceLock<Mutex<HashMap<i64, Arc<Session>>>> = OnceLock::new();
fn sessions() -> &'static Mutex<HashMap<i64, Arc<Session>>> {
    SESSIONS.get_or_init(|| Mutex::new(HashMap::new()))
}
fn session(id: i64) -> Result<Arc<Session>> {
    sessions()
        .lock()
        .map_err(err)?
        .get(&id)
        .cloned()
        .ok_or("Recovery session is closed".into())
}
fn thrown(env: &mut JNIEnv<'_>, message: String) {
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/io/IOException", message);
    }
}
#[no_mangle]
pub extern "system" fn Java_si_sarvesh_sift_NativeUndelete_open(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    fd: jint,
) -> jlong {
    let result = (|| {
        if fd < 0 {
            return Err("Invalid source descriptor".into());
        }
        let file = File::open(format!("/proc/self/fd/{fd}")).map_err(err)?;
        if !file.metadata().map_err(err)?.is_file() {
            return Err("Choose a regular, seekable image".into());
        }
        let id = NEXT.fetch_add(1, Ordering::Relaxed);
        sessions().lock().map_err(err)?.insert(
            id,
            Arc::new(Session {
                data: Mutex::new(Data { file, engine: None }),
                cancel: AtomicBool::new(false),
            }),
        );
        Ok(id)
    })();
    match result {
        Ok(id) => id,
        Err(e) => {
            thrown(&mut env, e);
            0
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_si_sarvesh_sift_NativeUndelete_scan(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    id: jlong,
    callback: JObject<'_>,
) -> jstring {
    let result: Result<jstring> = (|| {
        let session = session(id)?;
        let mut data = session.data.lock().map_err(err)?;
        let size = data.file.metadata().map_err(err)?.len();
        let engine = Undelete::scan(&data.file, size, &session.cancel, |done, total, found| {
            if env
                .call_method(
                    &callback,
                    "progress",
                    "(JJI)V",
                    &[
                        JValue::Long(done as i64),
                        JValue::Long(total as i64),
                        JValue::Int(found as i32),
                    ],
                )
                .is_err()
            {
                session.cancel.store(true, Ordering::Relaxed);
            }
        })?;
        let json = serde_json::to_string(&engine.scan).map_err(err)?;
        data.engine = Some(engine);
        Ok(env.new_string(json).map_err(err)?.into_raw())
    })();
    match result {
        Ok(value) => value,
        Err(e) => {
            thrown(&mut env, e);
            std::ptr::null_mut()
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_si_sarvesh_sift_NativeUndelete_validate(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    id: jlong,
    key: JString<'_>,
) {
    let result: Result<()> = (|| {
        let key: String = env.get_string(&key).map_err(err)?.into();
        let session = session(id)?;
        let data = session.data.lock().map_err(err)?;
        data.engine
            .as_ref()
            .ok_or("Scan first")?
            .validate(&data.file, &key, &session.cancel)
    })();
    if let Err(e) = result {
        thrown(&mut env, e);
    }
}
#[no_mangle]
pub extern "system" fn Java_si_sarvesh_sift_NativeUndelete_read(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    id: jlong,
    key: JString<'_>,
    offset: jlong,
    count: jint,
) -> jbyteArray {
    let result: Result<jbyteArray> = (|| {
        if offset < 0 || !(0..=1024 * 1024).contains(&count) {
            return Err("Invalid recovery read range".into());
        }
        let key: String = env.get_string(&key).map_err(err)?.into();
        let session = session(id)?;
        stop(&session.cancel)?;
        let data = session.data.lock().map_err(err)?;
        let mut output = vec![0; count as usize];
        let n = data.engine.as_ref().ok_or("Scan first")?.read(
            &data.file,
            &key,
            offset as u64,
            &mut output,
        )?;
        output.truncate(n);
        Ok(env.byte_array_from_slice(&output).map_err(err)?.into_raw())
    })();
    match result {
        Ok(value) => value,
        Err(e) => {
            thrown(&mut env, e);
            std::ptr::null_mut()
        }
    }
}
#[no_mangle]
pub extern "system" fn Java_si_sarvesh_sift_NativeUndelete_cancel(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    id: jlong,
) {
    match session(id) {
        Ok(s) => s.cancel.store(true, Ordering::Relaxed),
        Err(e) => thrown(&mut env, e),
    }
}
#[no_mangle]
pub extern "system" fn Java_si_sarvesh_sift_NativeUndelete_reset(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    id: jlong,
) {
    match session(id) {
        Ok(s) => s.cancel.store(false, Ordering::Relaxed),
        Err(e) => thrown(&mut env, e),
    }
}
#[no_mangle]
pub extern "system" fn Java_si_sarvesh_sift_NativeUndelete_close(
    mut env: JNIEnv<'_>,
    _: JClass<'_>,
    id: jlong,
) {
    match sessions().lock() {
        Ok(mut all) => {
            if let Some(s) = all.remove(&id) {
                s.cancel.store(true, Ordering::Relaxed);
            }
        }
        Err(e) => thrown(&mut env, e.to_string()),
    }
}
