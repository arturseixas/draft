import type { DraftDocument } from '../../types'
import { readSyncState, writeSyncState } from '../storage'
import { listRevisions, readRevision, uploadRevision } from './api'
import { captureChanges, emptySyncState, resolveRevisions, type SyncState } from './merge'
import type { DriveSession } from './google'

export async function synchronizeDrive(
  session: DriveSession,
  accountKey: string,
  getDocuments: () => DraftDocument[],
  applyRemote: (expected: DraftDocument[], incoming: DraftDocument[]) => Promise<Set<string>>,
) {
  const run = async () => {
    const stored = await readSyncState<SyncState>(accountKey)
    if (stored && stored.version !== 1)
      throw new Error(
        'Esta biblioteca usa uma versão de sincronização mais recente. Atualize o Draft.',
      )
    const snapshot = structuredClone(getDocuments())
    let state = captureChanges(stored ?? emptySyncState(), snapshot)
    // Persist the outbox before any request. An uncertain upload is deduplicated
    // by revision ID on the next pass, never replaced with a new write.
    await writeSyncState(accountKey, state)
    const files = await listRevisions(session)
    const known = new Map(state.revisions.map((revision) => [revision.id, revision]))
    const remoteIds = new Set(
      files.map((file) => file.name.replace(/^draft-revision-/, '').replace(/\.json$/, '')),
    )
    for (let index = 0; index < files.length; index += 4) {
      const unread = files
        .slice(index, index + 4)
        .filter(
          (file) => !known.has(file.name.replace(/^draft-revision-/, '').replace(/\.json$/, '')),
        )
      const received = await Promise.all(unread.map((file) => readRevision(session, file)))
      for (const revision of received) known.set(revision.id, revision)
    }
    const merged = await resolveRevisions([...known.values()])
    state.revisions = merged.revisions
    state.pending = [
      ...new Set([...state.pending, ...merged.added.map((revision) => revision.id)]),
    ].filter((id) => !remoteIds.has(id))
    await writeSyncState(accountKey, state)
    const byId = new Map(state.revisions.map((revision) => [revision.id, revision]))
    for (const id of [...state.pending]) {
      await uploadRevision(session, byId.get(id)!)
      state.pending = state.pending.filter((pending) => pending !== id)
      await writeSyncState(accountKey, state)
    }
    const deferred = await applyRemote(snapshot, merged.documents)
    const prior = new Map(state.baseline.map((doc) => [doc.id, doc]))
    const baseline = new Map(merged.documents.map((doc) => [doc.id, doc]))
    const bases = { ...merged.bases }
    for (const id of deferred) {
      if (prior.has(id)) baseline.set(id, prior.get(id)!)
      else baseline.delete(id)
      bases[id] = state.bases[id] ?? []
    }
    state = { ...state, baseline: [...baseline.values()], bases }
    await writeSyncState(accountKey, state)
    return {
      conflicts: merged.conflicts,
      deferred: deferred.size > 0,
      documents: merged.documents.length,
    }
  }
  return navigator.locks ? navigator.locks.request(`draft-drive:${accountKey}`, run) : run()
}
