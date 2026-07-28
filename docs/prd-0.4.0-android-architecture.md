# PRD：Codex额度 0.4.0 Android 新架构

## Problem Statement

现有公开版依赖 AstroBox 作为日常手机桥接。该方式虽然完成了 Windows、手机和小米手环 10 的额度链路验证，但会改变手环的日常连接关系，影响小米运动健康提供的消息通知、睡眠与健康数据同步。它还要求 AstroBox 长期存活，进程重启后可能重新配对，无法成为稳定、低摩擦的日常方案。

现有 Windows 端使用 Electron。用户观察到常驻内存可达 500MB 以上，而产品日常只需要额度采集、托盘管理、局域网同步和少量状态判断，运行成本与产品价值不匹配。

现有产品只展示额度，无法让离开电脑的用户知道官方 ChatGPT Windows 客户端中的本地代理任务正在处理、需要授权还是已经停止等待查看。用户需要一个只读、低打扰的 Android 与手环查看入口，但不希望它演变成第三方 ChatGPT 客户端，也不接受常驻状态栏通知、云端中转、遥测或读取完整对话。

## Solution

将产品统一升级为“Codex额度”0.4.0，由三个同版本组件组成：

- 轻量原生 Windows 托盘端：使用 Rust 与 Windows 原生 API，读取本机真实额度摘要，通过官方只读 Hook 获取可验证的 ChatGPT Windows 任务生命周期事件，判断 ChatGPT 客户端是否失焦，并在可信局域网中向已配对 Android 端同步最小化数据。
- 原生 Android App：使用 Kotlin 与原生 Android UI 技术直接接入小米 Wearable SDK，作为额度与任务看板、手机通知接收器和手环通知发送端；小米运动健康继续承担手环主连接、普通手机通知与健康同步。
- 小米手环 10 RPK：以连续竖向页面展示周额度、同步状态、最多三条任务和重置摘要，并接收 Android 端发送的原生通知。

产品保持严格只读。它不回复、批准、停止或新建 ChatGPT Windows 任务，不读取或传输完整提示词、回复、命令、文件路径、工具输出、Cookie 或账号内容。当前实现的例外是 Windows 本机低频读取 Codex 访问令牌并直连官方额度接口；令牌不进入日志、缓存、诊断、局域网同步、Android 或手环。项目不引入自建云服务。

开发先在本地完成五项高风险技术验证，再制作双端 UI 预览。只有技术门槛、自动测试和用户三端真机验收全部通过，并且用户明确回复“验收通过”，才提交、推送和发布 GitHub 正式版。

## User Stories

1. As a ChatGPT Windows user, I want to see my current weekly Codex quota on my Android phone, so that I can check usage without returning to the computer.
2. As a Xiaomi Smart Band 10 user, I want to see my weekly Codex quota on the band, so that the most important value is available at a glance.
3. As a quota-conscious user, I want to see only quota windows actually provided by the local data source, so that the product never invents missing limits.
4. As a quota-conscious user, I want to see the actual reset date and available reset count when present, so that I can plan future usage.
5. As a user viewing cached data, I want a clear cache label and last sync time, so that I do not mistake old values for live data.
6. As a user whose computer has been offline for more than 24 hours, I want a stronger stale-data warning while retaining the last trusted values, so that useful context is not erased.
7. As a privacy-conscious user, I want quota data to stay on my own Windows computer, Android phone and band, so that no project cloud service receives it.
8. As a Mi Fitness user, I want Mi Fitness to remain the band’s main daily connection, so that ordinary notifications, sleep records and health synchronization continue working.
9. As a new user, I want AstroBox to be needed only for initial or upgrade installation of the RPK, so that it is absent from the daily runtime path.
10. As an Android user without the band nearby, I want to use the phone quota and task board independently, so that band availability does not block the product.
11. As an Android user who has not installed the RPK, I want a clear guided installation message, so that I do not see an unexplained “APP not installed” error.
12. As a first-time user, I want a step-by-step setup flow, so that I can verify Mi Fitness, the RPK, permissions, computer pairing and the three-link connection one dependency at a time.
13. As a returning user, I want setup to stay out of the way after completion, so that the app opens directly to useful information.
14. As a user who denied a permission, I want unaffected features to remain usable, so that one refusal does not block the whole app.
15. As a user who denied a permission, I want a settings entry to request it again without repeated prompts, so that recovery remains available but not annoying.
16. As a ChatGPT Windows user, I want one local agent conversation to appear as one task, so that follow-up turns do not create duplicate task records.
17. As a ChatGPT Windows user, I want different local agent conversations to remain separate tasks, so that their states are independently understandable.
18. As a mobile user, I want each task to use its ChatGPT task name, so that the phone and band match the desktop task list.
19. As a privacy-conscious user, I want task names resolved from ChatGPT's local task index without reading the original prompt, so that prompts are neither used as titles nor stored or transmitted.
20. As a privacy-conscious user, I want to hide semantic task titles, so that the phone and band can show generic task numbers when needed.
21. As a mobile user, I want to know when a task is running, so that I can see progress without receiving an unnecessary alert.
22. As a mobile user, I want to know when a turn has stopped and is waiting for review, so that I can return without the app claiming why it stopped.
23. As a mobile user, I want to know when Codex is requesting system authorization, so that I can return to approve or reject it.
24. As a user who values truthful state, I do not want “waiting for review” split into completed, ordinary input or blocked labels, so that the product does not invent distinctions absent from official events.
25. As a user who values truthful state, I want unsupported states omitted rather than guessed from errors or retry counts, so that the app does not mislabel Codex activity.
26. As a mobile user, I want safe activity summaries such as “executing a command” or “using a browser,” so that I have context without exposing commands, paths or output.
27. As a band user, I want running tasks summarized simply as “处理中,” so that the small screen remains readable.
28. As a phone user, I want all running and needs-authorization conversations plus the ten most recent waiting-for-review conversations, so that current work is complete without becoming a permanent archive.
29. As a band user, I want at most three prioritized tasks, so that the task area remains legible.
30. As a band user, I want needs-authorization tasks prioritized above running and waiting-for-review tasks, so that the most actionable item is easiest to find.
31. As a band user, I want newer tasks to replace older lower-priority tasks when space is full, so that the three-item limit remains useful.
32. As a phone user, I want to hide a long-unresolved task from the local board, so that irrelevant items do not dominate the display.
33. As a phone user, I want a hidden task to reappear when it changes, so that new activity is not lost.
34. As a phone user, I want to delete a single waiting-for-review local record, so that I can clean up selectively.
35. As a phone user, I want to clear all waiting-for-review local records after confirmation, so that cleanup is fast but not accidental.
36. As a user cleaning the board, I want local cleanup to leave the ChatGPT Windows conversation untouched, so that the product remains read-only.
37. As a user actively working in the focused ChatGPT Windows client, I want task events to update silently, so that my band does not buzz while I am already watching the task.
38. As a user who later switches away from Codex, I do not want previously suppressed notifications replayed, so that stale alerts do not arrive.
39. As a user viewing the Android task board, I want phone and band notifications suppressed, so that the information is not announced twice.
40. As a user who closes ChatGPT on Windows, I want the board to show “ChatGPT未运行” without a notification, so that an intentional exit is not treated as a problem.
41. As a user who shuts down or disconnects the computer, I want the board to show “电脑离线” without a notification, so that routine network changes do not create noise.
42. As a user whose Hook is unavailable, I want “任务状态不可用” while quota synchronization continues, so that a task integration problem is not confused with a full outage.
43. As a user troubleshooting a connection, I want “ChatGPT未运行,” “电脑离线” and “任务状态不可用” to remain distinct, so that the recovery action is obvious.
44. As a user configuring reminders, I want modes for never, only when ChatGPT is unfocused and always, so that notification timing matches my work style.
45. As a user configuring reminders, I want separate toggles for waiting for review and needs authorization, so that I control which events matter.
46. As a user configuring reminders, I want separate phone and band channels, so that I can use either or both.
47. As a user receiving a waiting-for-review alert, I want the phone notification silent by default, so that routine turn stops are unobtrusive.
48. As a user receiving a needs-authorization alert, I want vibration without sound by default, so that actionable events stand out without being loud.
49. As an Android user, I want system notification channels to remain adjustable, so that Android’s own sound and vibration settings stay authoritative.
50. As a user with several near-simultaneous task events, I want them batched for ten seconds into one alert, so that the phone and band do not fire repeatedly.
51. As a user reconnecting after an outage, I do not want old waiting-for-review alerts replayed, so that reconnecting is not noisy.
52. As a user reconnecting with an unresolved authorization request, I want one merged alert, so that still-actionable work is not missed.
53. As a phone user tapping a single task alert, I want the corresponding read-only task detail, so that I can understand which task changed.
54. As a phone user tapping a merged alert, I want the prioritized task list, so that I can review all relevant updates.
55. As a band user tapping an alert, I want a deep link only when the Xiaomi SDK proves it reliable, so that the product does not promise a broken navigation path.
56. As a band user, I want a continuous vertical page without horizontal reading, so that the interface matches the device’s natural gesture model.
57. As a band user, I want system time, sync state and weekly quota fixed at the top hierarchy, so that opening the app immediately answers the main question.
58. As a band user, I want a compact task summary below quota, so that actionable state is visible without displacing the quota.
59. As a band user with no tasks, I want one “暂无任务” line and no empty list area, so that reset information moves upward while the quota header stays stable.
60. As a band user, I want task titles limited to two lines with ellipsis, so that long titles never force horizontal scrolling.
61. As a band user, I want status and relative time on a separate line, so that each task remains scannable.
62. As a band user, I want relative times such as “1分,” “25分,” “11小时” and “3天,” so that timestamps match the compact Codex style.
63. As a band user, I want status text plus restrained color coding, so that meaning is accessible without relying on color alone.
64. As a band user, I want the system edge-swipe back gesture to work at every scroll position, so that I can always exit the app.
65. As a phone user, I want the home screen to prioritize needs-authorization tasks when present, so that urgent work appears before quota.
66. As a phone user with no urgent tasks, I want quota to be the first visual priority, so that the product remains “Codex额度.”
67. As a phone user, I want computer, phone and band link states visible at the top, so that I can diagnose where synchronization stopped.
68. As a Windows user, I want the tray program to use at most 100MB across its full process tree after warmup, so that a simple quota utility stays lightweight.
69. As a Windows user, I want idle CPU below one percent and no leftover child process after exit, so that the tray program is unobtrusive.
70. As an existing user, I want my Windows startup preference preserved through the native migration, so that upgrading does not change boot behavior.
71. As an existing user, I want the new installer verified before old Electron files are removed, so that a failed upgrade does not destroy the working version.
72. As a Windows user, I want a concise first-run wizard and a reusable connection-and-diagnostics entry, so that setup is understandable without keeping a main window open.
73. As a Windows user, I want to inspect, repair or remove only the Hook installed by this product, so that unrelated Codex configuration remains untouched.
74. As a security-conscious user, I want one QR scan to establish an encrypted, authenticated pairing, so that task titles are not exposed on the LAN.
75. As a returning user, I want automatic reconnection after the first pairing, so that IP changes do not create daily setup steps.
76. As a user replacing a phone, I want the new phone to invalidate the previous phone, so that an old device cannot keep reading data.
77. As a user replacing the paired computer, I want an explicit replacement warning, so that data from multiple computers is never mixed accidentally.
78. As a user reinstalling Android, I want a fresh QR pairing rather than a cloud-restored secret, so that no account service is required.
79. As a security-conscious user, I want the pairing QR to be single-use and expire in five minutes, so that a captured code has limited value.
80. As a user on a changed LAN address, I want discovery to locate the paired computer but cryptographic identity to decide trust, so that an IP address is never treated as identity.
81. As a user checking for updates, I want networking to GitHub to occur only after I press “检查更新,” so that daily runtime remains local.
82. As a privacy-conscious user, I want no telemetry, advertising, analytics or automatic crash upload, so that the project never collects behavior data.
83. As a support-seeking user, I want a manually exported, privacy-trimmed diagnostic package, so that failures can be investigated without leaking task content.
84. As a user uninstalling Windows, I want the program, its startup entry, discovery data, firewall rule, credentials, cache, logs and its own Hook removed, so that no hidden residue remains.
85. As a user uninstalling Windows, I want ChatGPT conversations, the ChatGPT client and unrelated Hooks preserved, so that uninstalling this utility cannot damage my work.
86. As an Android user, I want the product described simply as Android without phone-model disclaimers, so that the public scope is clear.
87. As an iPhone user, I want the old release to remain available as an archive, so that historical users are not erased even though the new architecture is Android-only.
88. As a new GitHub visitor, I want the README to show only the current Android installation flow, so that I do not accidentally install the legacy architecture.
89. As a user downloading a release, I want Windows, APK and RPK versions aligned, so that I can identify a compatible set.
90. As a user updating compatible components, I want protocol compatibility to control runtime rather than an exact display-version match, so that harmless version differences do not block use.
91. As a user installing early Windows releases, I want a published SHA-256 and an honest unknown-publisher explanation, so that the project does not use dubious certificates.
92. As a user updating Android or the band, I want stable release signing keys, so that future versions can install over prior versions safely.
93. As a project owner, I want beta builds to remain local, so that incomplete architecture is not published as a public preview.
94. As a project owner, I want all five technical gates to pass before full implementation proceeds, so that the highest-risk assumptions fail cheaply.
95. As a project owner, I want Android and band visual previews approved before production UI changes, so that small-screen design is agreed before code is committed.
96. As a project owner, I want final Windows, Android and band real-device acceptance to require my explicit “验收通过,” so that automation never authorizes publication.

## Implementation Decisions

- The public product name is “Codex额度” on Windows, Android and Xiaomi Smart Band 10. “伴侣 App” and “副屏” are not public positioning.
- Version 0.4.0 is Android-only. iPhone and the daily AstroBox bridge are excluded from the new build, protocol, test matrix, documentation flow and release assets.
- The existing AstroBox implementation remains in source history and historical releases as legacy. It is not deleted during the 0.4.0 migration.
- The Windows component is a Rust native tray application using Windows native APIs. It must not use Electron, Tauri, WebView or another browser runtime.
- The Windows component retains quota collection, trusted cache, pause, refresh, pairing, revoke, startup preference, tray controls and per-user installation semantics.
- The Windows component adds official ChatGPT Windows client detection, foreground-window detection, read-only Hook integration, task-state reduction, encrypted Android synchronization and local diagnostics.
- The Hook is global, read-only and explicitly trusted once by the user. It forwards lifecycle events to the local Windows process and must not return control decisions, block Codex or access the public internet.
- Hook onboarding uses the visible ChatGPT desktop path “Settings → Hooks → User configuration” and enables `PreToolUse`, `PermissionRequest`, `UserPromptSubmit` and `Stop`. Public video and written tutorials show this settings path first; `/hooks` is only a troubleshooting fallback.
- Task identity is one local agent conversation in the official ChatGPT Windows client on the currently paired computer. Follow-up turns update the same task. Ordinary cloud chats, mobile ChatGPT, Remote, API, CLI and other computers are not read or synchronized.
- Task states are Running, Needs authorization and Waiting for review. They map directly to `UserPromptSubmit`/active execution, `PermissionRequest` and `Stop`. Waiting for review states only that the turn stopped; it does not claim completed, ordinary input or blocked semantics.
- Running activity may map to a small allowlist of safe summaries. Raw commands, arguments, paths, tool output and logs never enter the cross-device contract.
- The Windows process maps the Hook `session_id` to the latest matching `thread_name` in ChatGPT's local `session_index.jsonl`. It reads only the task identifier and task name, never the original request or transcript. The outbound title is sanitized and limited to 16 characters.
- When ChatGPT has not named a new task yet, Windows publishes the generic title “任务”. Later tool or stop events repeat the index lookup and replace it with the real task name without creating a duplicate task.
- The task contract contains only a stable conversation identifier, sanitized title or generic identifier, verified state, safe activity summary where applicable, and last update time.
- The quota contract contains only real source-provided windows, reset dates, reset-credit count and expiries, generation time, sync state and connection state. Missing values stay missing.
- “缓存” means the last trusted but potentially stale data. “离线” means the Android-to-Windows path is unavailable. These terms are not interchangeable.
- Offline cache remains visible indefinitely. It is gray immediately and gains a stronger “数据可能已过期” warning after 24 hours without synchronization.
- “ChatGPT未运行,” “电脑离线” and “任务状态不可用” are separate product states with separate recovery guidance.
- Closing Codex and losing the computer connection update the board silently. Neither condition produces a phone or band notification.
- Hook failure leaves quota available, retains the last task list as cached, disables new task reminders and exposes a non-destructive repair entry in the Windows tray.
- The Android component is a native Kotlin application using Jetpack Compose by default and direct Xiaomi Wearable SDK integration. Native XML may replace Compose if required by verified SDK compatibility without changing product behavior.
- Mi Fitness remains connected to the band for ordinary notifications and health synchronization. The Android App communicates through the Xiaomi wearable service rather than taking over the Bluetooth connection.
- AstroBox is used only to sideload or upgrade the RPK until a better official installation mechanism exists.
- The Android App can operate without an available band. Band setup is resumable later from settings.
- Permissions degrade independently. Wearable permission affects only the band path; Android notification permission affects only phone system notifications.
- The Android App does not use a foreground service or permanent status notification. It uses an ordinary background connection and heartbeat plus user-configured app lock, autostart and unrestricted battery policy.
- The Android App maintains all running and needs-authorization tasks plus the ten most recent waiting-for-review tasks. Persistence is encrypted locally and contains no original prompts or replies.
- The band receives at most three tasks ordered Needs authorization, Running and Waiting for review. Within equal priority, newer tasks replace older tasks when capacity is exceeded.
- Notification timing modes are Never, Only when ChatGPT is unfocused and Always. The default is Only when ChatGPT is unfocused.
- Notification type switches are Waiting for review and Needs authorization. Both are enabled by default. Running never notifies.
- Notification channel switches are Phone and Band. Both are enabled by default and remain independent.
- Waiting-for-review phone notifications are silent by default. Needs-authorization notifications vibrate without sound by default. Android system channels remain user-adjustable.
- Ten seconds is the batching window for simultaneous task alerts. Priority within a merged alert is Needs authorization and Waiting for review.
- Reconnection never replays waiting-for-review alerts. One merged reconnect alert is allowed only for an unresolved authorization request.
- When any official ChatGPT Windows client window is focused or the Android App is foregrounded, matching notifications are suppressed permanently rather than delayed.
- Phone notification navigation opens a read-only task detail for a single task and the prioritized task list for a batch.
- Band notification deep linking is enabled only if the Xiaomi SDK passes a reliable real-device test. Otherwise a tap opens the band app home.
- Pairing is one active Android phone per Windows computer and one active Windows computer per Android App.
- Pairing uses a five-minute, single-use QR credential, a persistent Windows identity key, pinned peer identity and encrypted transport. Discovery finds candidate addresses but never establishes trust.
- Android stores credentials with Android system secure storage. Windows stores only the material required to authenticate and revoke the phone.
- Pairing a new phone or computer explicitly replaces the old peer and invalidates the old credential.
- Reinstalling Android or changing phones requires a new QR pairing. No cloud credential, task or settings backup is introduced.
- Public-network classification does not disable the Windows service. Encryption and device authentication enforce the security boundary.
- The band UI is one continuous 212×520 vertical page. It must not require horizontal scrolling or custom horizontal navigation.
- The band hierarchy is system time and sync state, weekly quota, compact task summary, up to three task rows, then reset-credit information.
- With no tasks, the full list collapses to one “暂无任务” summary and reset information moves upward while the quota header remains fixed.
- Task titles use at most two lines with ellipsis. Status and compact relative time occupy a separate line.
- Status accents are Running blue, Needs authorization yellow, Waiting for review green and cache/unavailable gray, always accompanied by explicit high-contrast text.
- The system edge-swipe back gesture must work from every vertical scroll position.
- The Android home header always displays the computer, phone and band links. Needs-authorization tasks outrank quota; otherwise quota remains the first content priority.
- The Android quota view shows all actual source-provided quota windows and actual reset-credit expiries. It does not restore the unavailable five-hour quota or infer a reset to 100%.
- Windows, APK and RPK share one public product version. Protocol compatibility, not display-version equality alone, determines whether synchronization can continue.
- Local development uses 0.4.0 beta identifiers. No beta is uploaded to GitHub.
- Stable APK and RPK use fixed release signing identities with private keys stored locally and backed up offline in encrypted form. Debug signing stays separate.
- The early Windows installer may remain commercially unsigned. Releases provide SHA-256 checksums and transparent SmartScreen instructions.
- There is no background update check. A manual update action reads only GitHub release version and download information.
- There is no telemetry, advertising SDK, analytics or automatic crash upload.
- Diagnostics retain only versions, connection stage, error codes, retry counts, synchronization time and three-link state for seven days or a bounded size. Export is manual.
- Windows uninstall removes only product-owned files, startup configuration, discovery and firewall entries, credentials, caches, logs and the product-owned Hook.
- The README makes the Android architecture the only primary installation flow and links the unmaintained AstroBox release only from a brief legacy section.

## Testing Decisions

- Tests assert externally observable behavior at the highest practical seam. Internal helper implementation is not a contract unless it protects a security or privacy boundary.
- The five feasibility gates are completed before full implementation:
  1. Real official local Hook events in the ChatGPT Windows client identify conversations and every promised task state without parsing private databases or guessing.
  2. Real Windows foreground-window detection correctly suppresses events while any official ChatGPT client window has focus.
  3. A Rust tray prototype with Hook, quota, phone connection and task synchronization stays within the full-process-tree memory and CPU targets.
  4. A real Android device receives randomized task changes for four locked-screen hours without a foreground service or permanent notification.
  5. Real phone and Xiaomi Smart Band 10 notifications do not duplicate, Mi Fitness stays connected, ordinary notifications and health sync continue, and band deep-link capability receives a final supported/unsupported conclusion.
- Failure of a gate keeps all work local. The team adjusts implementation and repeats the gate. Any proposed change to product experience or scope returns to the user for a decision.
- Existing snapshot schema, cache, pairing, packaged-diagnostic, version-contract and band build tests are prior art. Their behavior is preserved or replaced by equivalent higher-level tests during migration.
- The Windows protocol tests cover quota minimization, task minimization, title sanitization, connection-state semantics, cache age, event deduplication, notification eligibility and Hook degradation.
- The encrypted pairing tests cover QR expiry, single use, peer replacement, IP changes, discovery spoofing, wrong computer identity, revoked credentials, reinstallation and automatic reconnect.
- Foreground tests use actual Windows processes and foreground handles, including multiple ChatGPT windows, minimization, focus transfer and ChatGPT exit. Matching uses the official Windows application package identity rather than process name, window title or internal-page inspection.
- Resource acceptance measures the entire Windows process tree after ten minutes of warmup with Codex, Hook, Android and task sync active. Private working set is at most 100MB.
- Resource acceptance allows temporary UI spikes for QR or diagnostics, but closing them returns the process tree below 100MB. Four-hour growth is at most 20MB.
- Idle CPU acceptance observes the full process tree for ten minutes after warmup and requires an average below one percent. Exit leaves no child process.
- Android contract tests cover independent permission degradation, task persistence limits, local cleanup, reconnect alert rules, foreground suppression, notification channels and replacement pairing.
- Android real-device background acceptance uses app lock, autostart, unrestricted battery, a locked screen and randomized Running, Waiting for review and Needs authorization events for four continuous hours.
- Android background failure must not be hidden by silently adding a foreground service.
- Wearable tests begin from the already proven `NotifyApi.sendNotify()` path and add coexistence checks for Mi Fitness connection, ordinary phone notifications and health synchronization.
- Band build tests cover 212×520 bounds, vertical overflow, absence of horizontal overflow, title line limits, compact time formatting, three-task prioritization, empty-task collapse and cache rendering.
- Band real-device acceptance covers normal, Needs authorization and cached/offline states, normal wearing-distance readability, continuous vertical scrolling and edge-swipe exit at the top, middle and bottom.
- Notification acceptance verifies the three timing modes, three task-type switches, two channel switches, ten-second batching, priority, deduplication, no delayed replay and reconnect behavior.
- End-to-end latency acceptance requires Hook event to Android board within two seconds, a single eligible alert within three seconds, a batch immediately after its ten-second window, band app trusted state within five seconds and LAN reconnect within fifteen seconds while the Android process remains alive.
- Migration tests verify preservation of the Windows startup preference, successful native startup before Electron removal, removal of old Chromium runtime files and manual rollback through historical releases.
- Installer tests cover per-user installation, first-run wizard, tray controls, manual update, repair, complete uninstall and absence of product-owned residual processes or firewall entries.
- Privacy tests reject prompts, replies, commands, arguments, file paths, tool output, tokens, secrets and account data from protocols, caches, notifications and diagnostics.
- Version and signing checks require matching public versions across Windows, APK and RPK, compatible protocol negotiation, release signatures and generated SHA-256 checksums.
- UI production implementation begins only after the user approves one recommended Android preview and one complete 212×520 band long-page preview.
- Automated testing cannot complete acceptance. The user personally verifies Windows, Android and Xiaomi Smart Band 10 and must explicitly reply “验收通过” before any commit, push or GitHub Release.

## Out of Scope

- iPhone support in the 0.4.0 architecture.
- Daily AstroBox bridging or support work for the legacy architecture.
- Support guarantees for wearable devices other than Xiaomi Smart Band 10.
- ChatGPT ordinary conversations, ChatGPT mobile quota extraction, API activity, Codex CLI activity or tasks from another computer.
- Replying to Codex, approving permissions, selecting answers, stopping or starting tasks, remote control or UI automation.
- Mirroring complete prompts, replies, command lines, paths, tool arguments, tool output, logs or project files.
- A cloud account, cloud synchronization, public relay, internet-exposed Windows endpoint or multi-user service.
- Multiple active phones per computer, multiple active computers per phone or merged task lists across computers.
- Foreground services, permanent Android status notifications or guaranteed delivery after Android kills the process.
- Background automatic update checks, forced upgrades, telemetry, advertising, behavioral analytics or automatic crash uploads.
- Automatic RPK installation by the Android App in the first release.
- Paid Windows code signing for the initial public release.
- Long-term task history, search, analytics, progress percentages or estimated completion time.
- A public beta release. Beta builds remain local.

## Further Notes

- The existing 0.3.1 implementation is the migration baseline, not the target architecture. Its quota-source truthfulness, trusted-cache semantics, per-user Windows installation and privacy filtering remain valuable prior art.
- ADR-002 establishes the native Rust Windows decision and its resource budget. ADR-003 establishes encrypted one-scan LAN pairing. The AstroBox credential ADR remains historical context for the legacy architecture.
- The official Xiaomi Wearable SDK notification probe has already shown that, after installing a matching RPK and granting permission, an Android app can send a notification that appears and vibrates on Xiaomi Smart Band 10.
- That probe does not yet complete the coexistence gate. The formal test must still prove that Mi Fitness remains connected and ordinary notifications and health synchronization continue.
- The official Codex Hook, Android background behavior and band notification deep link remain feasibility questions, not product decisions. Their evidence is recorded before full implementation.
- Public documentation describes phone support simply as Android and does not list phone models or use “兼容性待验证” wording.
- GitHub publication happens once, after all local technical validation, UI approval, automated checks and explicit three-device user acceptance are complete.
