//! libbannerin_guest.a: the C ABI declared in include/bannerin.h, over `bannerin_core::guest`.
//!
//! no_std, panic = abort, no allocation. Only the `bannerin_*` functions below are meant to be
//! called; the archive is linked with `--exclude-libs` so none of its symbols is exported from the
//! interposer.
#![cfg_attr(not(test), no_std)]
#![allow(clippy::missing_safety_doc)]

use core::ffi::{c_int, c_short, c_void};

use bannerin_core::abi::{AXES, BUTTONS};
use bannerin_core::guest::{self, IdentityView, LogFn, SlotStatsView, StatsView};
use bannerin_core::sys;

/// `struct bannerin_state`.
#[repr(C)]
pub struct BannerinState {
    pub buttons: [i32; BUTTONS],
    pub axes: [i32; AXES],
}

#[inline]
fn neg(e: i32) -> c_int {
    -e
}

#[no_mangle]
pub extern "C" fn bannerin_init() -> c_int {
    guest::init() as c_int
}

#[no_mangle]
pub unsafe extern "C" fn bannerin_attach(slot: c_int, open_flags: c_int, out_handle: *mut u32) -> c_int {
    if out_handle.is_null() {
        return neg(sys::EINVAL);
    }
    match guest::attach(slot, open_flags) {
        Ok((fd, h)) => {
            *out_handle = h;
            fd
        }
        Err(e) => neg(e),
    }
}

#[no_mangle]
pub extern "C" fn bannerin_detach(h: u32) -> c_int {
    match guest::detach(h) {
        Ok(()) => 0,
        Err(e) => neg(e),
    }
}

#[no_mangle]
pub extern "C" fn bannerin_fd(h: u32) -> c_int {
    guest::fd(h).unwrap_or_else(neg)
}

#[no_mangle]
pub extern "C" fn bannerin_slot(h: u32) -> c_int {
    guest::slot(h).unwrap_or_else(neg)
}

#[no_mangle]
pub extern "C" fn bannerin_is_removed(h: u32) -> c_int {
    match guest::is_removed(h) {
        Ok(r) => r as c_int,
        Err(e) => neg(e),
    }
}

#[no_mangle]
pub extern "C" fn bannerin_has_pending(h: u32) -> c_int {
    match guest::has_pending(h) {
        Ok(p) => p as c_int,
        Err(e) => neg(e),
    }
}

#[no_mangle]
pub extern "C" fn bannerin_poll_revents(h: u32, events: c_short) -> c_short {
    guest::poll_revents(h, events)
}

#[no_mangle]
pub unsafe extern "C" fn bannerin_read(h: u32, buf: *mut c_void, len: usize, nonblocking: c_int) -> isize {
    match guest::read(h, buf as *mut u8, len, nonblocking != 0) {
        Ok(n) => n as isize,
        Err(e) => -(e as isize),
    }
}

#[no_mangle]
pub unsafe extern "C" fn bannerin_current_state(h: u32, out: *mut BannerinState) -> c_int {
    if out.is_null() {
        return neg(sys::EINVAL);
    }
    let mut b = [0i32; BUTTONS];
    let mut a = [0i32; AXES];
    match guest::current_state(h, &mut b, &mut a) {
        Ok(()) => {
            (*out).buttons = b;
            (*out).axes = a;
            0
        }
        Err(e) => neg(e),
    }
}

unsafe fn copy_out<T>(v: &T, out: *mut T, size: usize) {
    let n = size.min(core::mem::size_of::<T>());
    core::ptr::copy_nonoverlapping(v as *const T as *const u8, out as *mut u8, n);
}

#[no_mangle]
pub unsafe extern "C" fn bannerin_get_stats(out: *mut StatsView, size: usize) -> c_int {
    if out.is_null() {
        return neg(sys::EINVAL);
    }
    copy_out(&guest::stats(), out, size);
    0
}

#[no_mangle]
pub unsafe extern "C" fn bannerin_slot_stats(slot: c_int, out: *mut SlotStatsView, size: usize) -> c_int {
    if out.is_null() {
        return neg(sys::EINVAL);
    }
    match guest::slot_stats(slot) {
        Ok(v) => {
            copy_out(&v, out, size);
            0
        }
        Err(e) => neg(e),
    }
}

/// `int bannerin_identity(bannerin_handle h, struct bannerin_identity *out, size_t size)`
#[no_mangle]
pub unsafe extern "C" fn bannerin_identity(h: u32, out: *mut IdentityView, size: usize) -> c_int {
    if out.is_null() {
        return neg(sys::EINVAL);
    }
    match guest::identity(h) {
        Ok(v) => {
            copy_out(&v, out, size);
            0
        }
        Err(e) => neg(e),
    }
}

/// `int bannerin_slot_identity(int slot, struct bannerin_identity *out, size_t size)`
#[no_mangle]
pub unsafe extern "C" fn bannerin_slot_identity(slot: c_int, out: *mut IdentityView, size: usize) -> c_int {
    if out.is_null() {
        return neg(sys::EINVAL);
    }
    match guest::slot_identity(slot) {
        Ok(v) => {
            copy_out(&v, out, size);
            0
        }
        Err(e) => neg(e),
    }
}

#[no_mangle]
pub extern "C" fn bannerin_set_logger(f: Option<LogFn>) {
    guest::set_logger(f)
}

#[no_mangle]
pub extern "C" fn bannerin_api_version() -> u32 {
    guest::API_VERSION
}

#[no_mangle]
pub extern "C" fn bannerin_axis_hz() -> u32 {
    guest::axis_hz()
}

#[no_mangle]
pub extern "C" fn bannerin_axis_auto() -> c_int {
    (guest::axis_config() == guest::AXIS_AUTO) as c_int
}

#[cfg(not(test))]
#[panic_handler]
fn panic(_info: &core::panic::PanicInfo) -> ! {
    sys::abort()
}

// Never called (panic = abort); defined so an unwind table left in a precompiled `core` object
// can never turn into an undefined symbol when the archive is linked.
#[cfg(not(test))]
#[no_mangle]
pub extern "C" fn rust_eh_personality() {}

#[cfg(test)]
mod tests;
