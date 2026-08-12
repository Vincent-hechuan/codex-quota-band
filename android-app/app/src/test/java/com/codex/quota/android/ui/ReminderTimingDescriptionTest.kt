package com.codex.quota.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ReminderTimingDescriptionTest {
  @Test
  fun descriptionMatchesEachTimingChoice() {
    assertEquals("不发送任务提醒", reminderTimingDescription(ReminderTiming.Never))
    assertEquals(
      "仅当 ChatGPT 客户端处于后台时提醒",
      reminderTimingDescription(ReminderTiming.Unfocused),
    )
    assertEquals(
      "无论 ChatGPT 客户端是否在前台都提醒",
      reminderTimingDescription(ReminderTiming.Always),
    )
  }
}
