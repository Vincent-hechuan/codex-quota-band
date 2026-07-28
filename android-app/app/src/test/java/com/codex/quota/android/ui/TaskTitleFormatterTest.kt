package com.codex.quota.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TaskTitleFormatterTest {
  @Test
  fun `adds an ellipsis only when the safe title reaches the upstream limit`() {
    assertEquals("审计 Android UI 与信息...", TaskTitleFormatter.display("审计 Android UI 与信息", hidden = false))
    assertEquals("短任务", TaskTitleFormatter.display("短任务", hidden = false))
  }

  @Test
  fun `never exposes a title when local title hiding is enabled`() {
    assertEquals("任务标题已隐藏", TaskTitleFormatter.display("审计 Android UI 与信息", hidden = true))
  }
}
