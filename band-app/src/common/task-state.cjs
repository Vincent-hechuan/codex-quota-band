const CHATGPT_STATES = new Set(['running', 'not_running', 'hook_unavailable'])
const TASK_STATES = new Set(['running', 'needs_authorization', 'waiting_for_review'])
const ACTIVITIES = new Set(['executing_command', 'modifying_files', 'using_browser'])

function requireFiniteTimestamp(value, name) {
  if (!Number.isFinite(value) || value < 0) throw new Error(`invalid ${name}`)
  return value
}

function sanitizeTaskSnapshotForBand(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error('invalid task snapshot')
  }
  if (!CHATGPT_STATES.has(value.chatGptState)) throw new Error('invalid ChatGPT state')
  if (!Array.isArray(value.tasks) || value.tasks.length > 3) throw new Error('invalid task list')

  return {
    generatedAtMs: requireFiniteTimestamp(value.generatedAtMs, 'task snapshot time'),
    chatGptState: value.chatGptState,
    tasks: value.tasks.map((task) => {
      if (!task || typeof task !== 'object' || Array.isArray(task)) {
        throw new Error('invalid task')
      }
      const title = typeof task.title === 'string' ? task.title.trim() : ''
      if (!title || Array.from(title).length > 16 || /[\u0000-\u001f\u007f]/.test(title)) {
        throw new Error('invalid task title')
      }
      if (!TASK_STATES.has(task.state)) throw new Error('invalid task state')
      if (task.activity !== undefined && !ACTIVITIES.has(task.activity)) {
        throw new Error('invalid task activity')
      }
      const sanitized = {
        title,
        state: task.state,
        updatedAtMs: requireFiniteTimestamp(task.updatedAtMs, 'task update time')
      }
      if (task.activity !== undefined) sanitized.activity = task.activity
      return sanitized
    })
  }
}

function relativeTimeText(updatedAtMs, now = new Date()) {
  const elapsedMs = Math.max(0, now.getTime() - updatedAtMs)
  const minutes = Math.floor(elapsedMs / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes}分`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours}小时`
  return `${Math.floor(hours / 24)}天`
}

function taskStatusView(task) {
  if (task.state === 'needs_authorization') {
    return { statusText: '需要授权', groupText: '需要授权', tone: 'danger', order: 0 }
  }
  if (task.state === 'waiting_for_review') {
    return { statusText: '等待查看', groupText: '等待查看', tone: 'waiting', order: 2 }
  }
  return {
    statusText: '处理中',
    groupText: '处理中',
    tone: 'running',
    order: 1
  }
}

function createTaskView(snapshot, now = new Date()) {
  if (!snapshot) {
    return { summaryText: '任务状态不可用', items: [] }
  }
  const sanitized = sanitizeTaskSnapshotForBand(snapshot)
  let summaryText = `${sanitized.tasks.length}项任务`
  if (sanitized.chatGptState === 'not_running') summaryText = 'ChatGPT 未运行'
  if (sanitized.chatGptState === 'hook_unavailable') summaryText = '任务状态不可用'
  if (sanitized.chatGptState === 'running' && sanitized.tasks.length === 0) {
    summaryText = '暂无任务'
  }
  const orderedItems = sanitized.tasks
    .map((task) => {
      const status = taskStatusView(task)
      return {
        title: task.title,
        statusText: status.statusText,
        groupText: status.groupText,
        tone: status.tone,
        groupTone: status.tone,
        timeText: relativeTimeText(task.updatedAtMs, now),
        order: status.order
      }
    })
    .sort((left, right) => left.order - right.order)

  const groupCounts = orderedItems.reduce((counts, item) => {
    counts[item.tone] = (counts[item.tone] || 0) + 1
    return counts
  }, {})
  const seenGroups = {}
  const items = orderedItems.map(({ order, ...item }, index) => {
    const showGroup = !seenGroups[item.tone]
    seenGroups[item.tone] = true
    return {
      ...item,
      groupCount: String(groupCounts[item.tone]),
      showGroup,
      layoutClass: showGroup ? 'with-group' : 'row',
      showDivider: index < orderedItems.length - 1
    }
  })

  return {
    summaryText,
    items
  }
}

module.exports = {
  createTaskView,
  relativeTimeText,
  sanitizeTaskSnapshotForBand
}
