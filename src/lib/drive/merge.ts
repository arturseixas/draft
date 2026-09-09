import type { DraftDocument } from '../../types'
import { newId } from '../id'

export interface Revision {
  version: 1
  id: string
  documentId: string
  parents: string[]
  document: DraftDocument | null
}
export interface SyncState {
  version: 1
  revisions: Revision[]
  pending: string[]
  baseline: DraftDocument[]
  bases: Record<string, string[]>
}
export const emptySyncState = (): SyncState => ({
  version: 1,
  revisions: [],
  pending: [],
  baseline: [],
  bases: {},
})
export const equalDocument = (a: DraftDocument | undefined, b: DraftDocument | undefined) =>
  JSON.stringify(a) === JSON.stringify(b)
const comparable = (doc: DraftDocument) =>
  JSON.stringify([doc.title, doc.mode, doc.favorite, doc.content])

/** Capture a local edit against the exact revisions the user has actually seen. */
export function captureChanges(state: SyncState, documents: DraftDocument[]): SyncState {
  const next = structuredClone(state)
  const previous = new Map(state.baseline.map((doc) => [doc.id, doc]))
  const current = new Map(documents.map((doc) => [doc.id, doc]))
  for (const id of new Set([...previous.keys(), ...current.keys()])) {
    if (equalDocument(previous.get(id), current.get(id))) continue
    const revision: Revision = {
      version: 1,
      id: newId(),
      documentId: id,
      parents: state.bases[id] ?? [],
      document: current.get(id) ?? null,
    }
    next.revisions.push(revision)
    next.pending.push(revision.id)
    next.bases[id] = [revision.id]
  }
  next.baseline = structuredClone(documents)
  return next
}

export function revisionHeads(revisions: Revision[]): Map<string, Revision[]> {
  const grouped = new Map<string, Revision[]>()
  for (const revision of new Map(revisions.map((r) => [r.id, r])).values()) {
    const group = grouped.get(revision.documentId) ?? []
    group.push(revision)
    grouped.set(revision.documentId, group)
  }
  for (const [id, group] of grouped) {
    const parents = new Set(group.flatMap((revision) => revision.parents))
    grouped.set(
      id,
      group.filter((revision) => !parents.has(revision.id)),
    )
  }
  return grouped
}
async function digest(text: string) {
  const bytes = new TextEncoder().encode(text)
  return [...new Uint8Array(await crypto.subtle.digest('SHA-256', bytes))]
    .map((byte) => byte.toString(16).padStart(2, '0'))
    .join('')
}

/** Immutable revisions avoid last-writer-wins data loss across devices. Conflicts
 * become ordinary, independently editable documents with deterministic IDs. */
export async function resolveRevisions(revisions: Revision[]) {
  const unique = new Map(revisions.map((revision) => [revision.id, revision]))
  const added: Revision[] = []
  let conflicts = 0
  for (const [documentId, heads] of revisionHeads([...unique.values()])) {
    if (heads.length < 2) continue
    const active = heads
      .filter((head) => head.document)
      .sort((a, b) => b.document!.updatedAt - a.document!.updatedAt || b.id.localeCompare(a.id))
    const winner = active[0]?.document ?? null
    const seen = new Set(winner ? [comparable(winner)] : [])
    for (const head of active.slice(1)) {
      if (seen.has(comparable(head.document!))) continue
      seen.add(comparable(head.document!))
      const id = `conflict-${head.id}`
      // Another device may have already materialized (and edited/deleted) this copy.
      if (!unique.has(id)) {
        added.push({
          version: 1,
          id,
          documentId: id,
          parents: [],
          document: {
            ...head.document!,
            id,
            title: `${head.document!.title.slice(0, 225)} (conflito)`,
          },
        })
        conflicts++
      }
    }
    const parents = heads.map((head) => head.id).sort()
    const id = `merge-${await digest(parents.join('\n'))}`
    added.push({ version: 1, id, documentId, parents, document: winner })
  }
  for (const revision of added) unique.set(revision.id, revision)
  const heads = revisionHeads([...unique.values()])
  const documents = [...heads.values()].flatMap((group) =>
    group[0]?.document ? [group[0].document] : [],
  )
  const bases = Object.fromEntries(
    [...heads].map(([id, group]) => [id, group.map((revision) => revision.id)]),
  )
  return { revisions: [...unique.values()], added, documents, bases, conflicts }
}

export function reconcileDocuments(
  current: DraftDocument[],
  expected: DraftDocument[],
  incoming: DraftDocument[],
) {
  const original = new Map(expected.map((doc) => [doc.id, doc]))
  const local = new Map(current.map((doc) => [doc.id, doc]))
  const remote = new Map(incoming.map((doc) => [doc.id, doc]))
  const deferred = new Set<string>()
  const documents: DraftDocument[] = []
  for (const id of new Set([...original.keys(), ...local.keys(), ...remote.keys()])) {
    if (!equalDocument(original.get(id), local.get(id))) {
      deferred.add(id)
      if (local.has(id)) documents.push(local.get(id)!)
    } else if (remote.has(id)) documents.push(remote.get(id)!)
  }
  return { documents, deferred }
}
