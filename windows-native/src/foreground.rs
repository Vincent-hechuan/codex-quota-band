pub fn official_chatgpt_package(full_name: &str) -> bool {
    matches!(
        full_name
            .split('_')
            .next()
            .unwrap_or_default()
            .to_ascii_lowercase()
            .as_str(),
        "openai.codex" | "openai.chatgpt"
    )
}

#[cfg(windows)]
pub fn chatgpt_is_foreground() -> bool {
    use windows_sys::Win32::Foundation::{CloseHandle, ERROR_INSUFFICIENT_BUFFER};
    use windows_sys::Win32::Storage::Packaging::Appx::GetPackageFullName;
    use windows_sys::Win32::System::Threading::{OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION};
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        GetForegroundWindow, GetWindowThreadProcessId,
    };

    unsafe {
        let window = GetForegroundWindow();
        if window.is_null() {
            return false;
        }
        let mut process_id = 0_u32;
        GetWindowThreadProcessId(window, &mut process_id);
        if process_id == 0 {
            return false;
        }
        let process = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, process_id);
        if process.is_null() {
            return false;
        }
        let mut length = 0_u32;
        let first = GetPackageFullName(process, &mut length, std::ptr::null_mut());
        if first != ERROR_INSUFFICIENT_BUFFER || length < 2 || length > 512 {
            CloseHandle(process);
            return false;
        }
        let mut buffer = vec![0_u16; length as usize];
        let result = GetPackageFullName(process, &mut length, buffer.as_mut_ptr());
        CloseHandle(process);
        if result != 0 || length < 2 {
            return false;
        }
        let package = String::from_utf16_lossy(&buffer[..length.saturating_sub(1) as usize]);
        official_chatgpt_package(&package)
    }
}

#[cfg(not(windows))]
pub fn chatgpt_is_foreground() -> bool {
    false
}
