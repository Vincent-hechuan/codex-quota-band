package com.codex.quota.android.ui

/**
 * Windows sends an already-sanitized task-title summary. When it reaches the upstream safe limit,
 * make the truncation explicit without requesting or retaining any extra title content.
 */
object TaskTitleFormatter {
  private const val UPSTREAM_SAFE_TITLE_LIMIT = 16

  fun display(title: String, hidden: Boolean): String =
    when {
      hidden -> "任务标题已隐藏"
      title.length >= UPSTREAM_SAFE_TITLE_LIMIT -> "$title..."
      else -> title
    }
}
