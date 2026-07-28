Unicode true
RequestExecutionLevel user

!include "MUI2.nsh"
!define MUI_ABORTWARNING
!define MUI_FINISHPAGE_RUN "$INSTDIR\CodexQuota.exe"
!define MUI_FINISHPAGE_RUN_TEXT "完成后启动 Codex额度并打开新手引导"
!define MUI_FINISHPAGE_RUN_FUNCTION "LaunchCodexQuota"

!ifndef APP_VERSION
  !error "APP_VERSION is required"
!endif
!ifndef APP_BINARY
  !error "APP_BINARY is required"
!endif
!ifndef OUTPUT_PATH
  !error "OUTPUT_PATH is required"
!endif
!ifndef APP_VERSION_INFO
  !error "APP_VERSION_INFO is required"
!endif

!define APP_NAME "Codex额度"
!define APP_ID "CodexQuota"
!define UNINSTALL_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}"

Name "${APP_NAME} ${APP_VERSION}"
OutFile "${OUTPUT_PATH}"
InstallDir "$LOCALAPPDATA\Programs\${APP_ID}"
InstallDirRegKey HKCU "Software\${APP_ID}" "InstallDir"

VIProductVersion "${APP_VERSION_INFO}"
VIAddVersionKey "ProductName" "${APP_NAME}"
VIAddVersionKey "ProductVersion" "${APP_VERSION}"
VIAddVersionKey "FileVersion" "${APP_VERSION}"
VIAddVersionKey "FileDescription" "${APP_NAME} Windows 本地服务"
VIAddVersionKey "LegalCopyright" "Copyright © 2026 Codex Quota"

!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH
!insertmacro MUI_LANGUAGE "SimpChinese"
UninstPage uninstConfirm
UninstPage instfiles

Section "Install"
  ; The installed tray process keeps its own EXE open. Ask only our process
  ; to close before NSIS replaces the file; do not touch unrelated programs.
  DetailPrint "正在关闭旧版 Codex额度..."
  ExecWait '"$SYSDIR\taskkill.exe" /IM CodexQuota.exe /T /F'
  Sleep 1500
  SetOutPath "$INSTDIR"
  File /oname=CodexQuota.exe "${APP_BINARY}"
  WriteUninstaller "$INSTDIR\Uninstall.exe"

  WriteRegStr HKCU "Software\${APP_ID}" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayName" "${APP_NAME} ${APP_VERSION}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "Publisher" "Codex Quota"
  WriteRegStr HKCU "${UNINSTALL_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKCU "${UNINSTALL_KEY}" "DisplayIcon" "$INSTDIR\CodexQuota.exe"
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINSTALL_KEY}" "NoRepair" 1

  CreateDirectory "$SMPROGRAMS\${APP_NAME}"
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "$INSTDIR\CodexQuota.exe"
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\卸载 ${APP_NAME}.lnk" "$INSTDIR\Uninstall.exe"

  ; Keep hook installation in the same user-level install flow. This is
  ; idempotent and preserves other products' hooks in hooks.json.
  ExecWait '"$INSTDIR\CodexQuota.exe" --install-hooks'
SectionEnd

Section "Uninstall"
  Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
  Delete "$SMPROGRAMS\${APP_NAME}\卸载 ${APP_NAME}.lnk"
  RMDir "$SMPROGRAMS\${APP_NAME}"
  Delete "$INSTDIR\CodexQuota.exe"
  Delete "$INSTDIR\Uninstall.exe"
  DeleteRegKey HKCU "${UNINSTALL_KEY}"
  DeleteRegKey HKCU "Software\${APP_ID}"
  RMDir "$INSTDIR"
SectionEnd

Function LaunchCodexQuota
  ExecShell "open" "$INSTDIR\CodexQuota.exe" "--show-onboarding"
FunctionEnd
