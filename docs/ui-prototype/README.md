# Codex额度 Android UI 原型（非正式实现）

这是用于确认 Android UI 改造方向的静态、只读原型。它不读取真实数据、不包含 Android
运行逻辑，也不会进入 APK。

运行（仓库根目录）：

```powershell
python -m http.server 4173 -d docs/ui-prototype
```

然后在浏览器打开 `http://localhost:4173`。页面顶部的“演示画面”按钮只切换静态示例：正常首页、需要授权、任务、设置和离线缓存。

确认的设计决策应记录在评审结果中；实施前不要把本文件直接并入 Compose。
