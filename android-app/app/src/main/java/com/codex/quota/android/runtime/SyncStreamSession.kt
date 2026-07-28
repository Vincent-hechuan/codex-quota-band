package com.codex.quota.android.runtime

import com.codex.quota.android.protocol.SyncStreamFrame
import com.codex.quota.android.protocol.SyncStreamWireContract
import com.codex.quota.android.protocol.TaskSnapshot

enum class StreamIngestResult {
  Accepted,
  IgnoredStale,
  IgnoredWrongConnection,
}

class SyncStreamSession(
  private val repository: RuntimeStateRepository,
  private val onTasksAccepted: (TaskSnapshot, reconnect: Boolean) -> Unit = { _, _ -> },
) {
  private val lock = Any()
  private var connectionId: String? = null
  private var quotaVersion: Int? = null
  private var lastSequence: Long? = null
  private var firstSnapshotForConnection = true

  fun ingest(payload: String): StreamIngestResult =
    synchronized(lock) {
      when (val frame = SyncStreamWireContract.decode(payload, quotaVersion)) {
        is SyncStreamFrame.ServerHello -> acceptHello(frame)
        is SyncStreamFrame.Snapshot ->
          acceptSequenced(frame.connectionId, frame.sequence) {
            repository.markTransportDataReceived()
            repository.ingestQuota(frame.quota)
            if (repository.ingestTasks(frame.tasks) == IngestResult.Accepted) {
              onTasksAccepted(frame.tasks, firstSnapshotForConnection)
            }
            firstSnapshotForConnection = false
          }
        is SyncStreamFrame.Heartbeat ->
          acceptSequenced(frame.connectionId, frame.sequence) {
            repository.markTransportDataReceived()
          }
      }
    }

  fun disconnect() {
    synchronized(lock) {
      connectionId = null
      quotaVersion = null
      lastSequence = null
      repository.markTransportDisconnected()
    }
  }

  fun currentConnectionId(): String? = synchronized(lock) { connectionId }

  private fun acceptHello(frame: SyncStreamFrame.ServerHello): StreamIngestResult {
    if (frame.connectionId == connectionId) return StreamIngestResult.IgnoredStale
    repository.markTransportDisconnected()
    connectionId = frame.connectionId
    quotaVersion = frame.quotaVersion
    lastSequence = null
    firstSnapshotForConnection = true
    repository.markTransportConnected()
    return StreamIngestResult.Accepted
  }

  private fun acceptSequenced(
    incomingConnectionId: String,
    sequence: Long,
    apply: () -> Unit,
  ): StreamIngestResult {
    if (incomingConnectionId != connectionId) return StreamIngestResult.IgnoredWrongConnection
    val previousSequence = lastSequence
    if (previousSequence != null && sequence <= previousSequence) {
      return StreamIngestResult.IgnoredStale
    }
    apply()
    lastSequence = sequence
    return StreamIngestResult.Accepted
  }
}
