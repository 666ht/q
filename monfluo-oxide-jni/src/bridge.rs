use std::{
    any::TypeId,
    mem::ManuallyDrop,
    sync::{Arc, Weak},
};

pub fn into_java_handle<T>(arc: Arc<T>) -> usize {
    let weak = Arc::downgrade(&arc);
    // Do not decrement strong count, so that T is not dropped
    let _ = ManuallyDrop::new(arc);
    Weak::into_raw(weak).addr()
}

pub fn from_java_handle<T: 'static>(handle: usize) -> Arc<T> {
    try_from_java_handle(handle).unwrap_or_else(|| {
        panic!(
            "Could not construct {:?} from java handle {}",
            TypeId::of::<T>(),
            handle
        )
    })
}

pub fn try_from_java_handle<T>(handle: usize) -> Option<Arc<T>> {
    let ptr: *const T = handle as *const () as *const T;
    // SAFETY: absolutely and utterly unsafe
    // If java side gives as bad handle (that we did not construct), bad things will happen
    let weak: Weak<T> = unsafe { Weak::from_raw(ptr) };
    let maybe_arc = weak.upgrade();
    // Do not decrement weak count, so that even after T is dropped we can still safely construct Weak<T>
    let _ = ManuallyDrop::new(weak);
    maybe_arc
}

pub fn drop<T>(arc: Arc<T>) {
    let raw = Arc::into_raw(arc);
    // SAFETY: absolutely and utterly unsafe
    // if this is called more than once, bad things will happen
    unsafe {
        // Decrement once, for the reference that we got as the function argument
        Arc::decrement_strong_count(raw);
        // Decrement once more, for the additional strong reference created in `into_java_handle`
        Arc::decrement_strong_count(raw);
    }
}

pub fn deallocate<T>(handle: usize) {
    let ptr: *const T = handle as *const () as *const T;
    // SAFETY: absolutely and utterly unsafe
    // If java side gives as bad handle (that we did not construct) or deallocates twice, bad things will happen
    let weak: Weak<T> = unsafe { Weak::from_raw(ptr) };
    // this should be called after `drop` function, so the last remaining reference is a weak reference created in `into_java_handle`
    std::mem::drop(weak);
}
