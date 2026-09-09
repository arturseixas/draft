import { test, expect } from '@playwright/test'
import {
  captureChanges,
  emptySyncState,
  resolveRevisions,
  reconcileDocuments,
  type Revision,
} from '../src/lib/drive/merge'
import type { DraftDocument } from '../src/types'
const doc = (text: string, updatedAt = 1): DraftDocument => ({
  id: 'story',
  title: 'História',
  mode: 'free',
  createdAt: 1,
  updatedAt,
  favorite: false,
  content: { type: 'doc', content: [{ type: 'paragraph', content: [{ type: 'text', text }] }] },
})
const revision = (
  id: string,
  document: DraftDocument | null,
  parents: string[] = [],
): Revision => ({ version: 1, id, documentId: 'story', parents, document })
test('Drive: sequential edits supersede ancestors and deletion persists', async () => {
  const history = [revision('a', doc('Primeira')), revision('b', doc('Segunda'), ['a'])]
  expect((await resolveRevisions(history)).documents[0]).toEqual(doc('Segunda'))
  expect((await resolveRevisions([...history, revision('c', null, ['b'])])).documents).toEqual([])
})
test('Drive: concurrent edits preserve both versions and converge independently', async () => {
  const history = [
    revision('a', doc('Original')),
    revision('b', doc('Computador', 2), ['a']),
    revision('c', doc('Celular', 3), ['a']),
  ]
  const left = await resolveRevisions(history)
  const right = await resolveRevisions([...history].reverse())
  expect(left.documents).toHaveLength(2)
  expect(left.documents.find((d) => d.id === 'story')).toEqual(doc('Celular', 3))
  expect(left.documents.find((d) => d.id === 'conflict-b')?.content).toEqual(
    doc('Computador').content,
  )
  expect(new Set(left.added.map((r) => r.id))).toEqual(new Set(right.added.map((r) => r.id)))
  const again = await resolveRevisions(left.revisions)
  expect(again.added).toHaveLength(0)
  expect(again.documents).toHaveLength(2)
})
test('Drive: deleting a conflict copy does not recreate it', async () => {
  const merged = await resolveRevisions([revision('a', doc('A', 1)), revision('b', doc('B', 2))])
  const removed: Revision = {
    version: 1,
    id: 'delete-conflict',
    documentId: 'conflict-a',
    parents: ['conflict-a'],
    document: null,
  }
  expect((await resolveRevisions([...merged.revisions, removed])).documents).toHaveLength(1)
})
test('Drive: concurrent deletion never discards an unseen edit', async () => {
  const merged = await resolveRevisions([
    revision('a', doc('A')),
    revision('delete', null, ['a']),
    revision('edit', doc('Ainda aqui'), ['a']),
  ])
  expect(merged.documents).toEqual([doc('Ainda aqui')])
})
test('Drive: changes typed during a request remain local and pending', () => {
  const result = reconcileDocuments([doc('Digitando')], [doc('Antes')], [doc('Outro dispositivo')])
  expect(result.documents).toEqual([doc('Digitando')])
  expect(result.deferred.has('story')).toBe(true)
  const captured = captureChanges(emptySyncState(), [doc('Antes')])
  expect(captureChanges(captured, [doc('Antes')]).pending).toEqual(captured.pending)
  const next = captureChanges(captured, [doc('Depois')])
  expect(next.revisions.at(-1)?.parents).toEqual([captured.revisions[0].id])
})
