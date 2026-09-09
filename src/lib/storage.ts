import { openDB, type DBSchema, type IDBPDatabase } from 'idb'
import type { DraftDocument } from '../types'
import { createExamples } from './examples'
interface DraftDB extends DBSchema {
  documents: { key: string; value: DraftDocument }
  meta: { key: string; value: unknown }
}
const journalPrefix = 'draft:recovery:'
let connection: Promise<IDBPDatabase<DraftDB>> | undefined
const db = () =>
  (connection ??= openDB<DraftDB>('draft-writing', 1, {
    upgrade(database) {
      database.createObjectStore('documents', { keyPath: 'id' })
      database.createObjectStore('meta')
    },
    terminated() {
      connection = undefined
    },
  }).catch((error) => {
    connection = undefined
    throw error
  }))
let queue: Promise<unknown> = Promise.resolve()
export async function loadDocuments(): Promise<DraftDocument[]> {
  const database = await db()
  for (const key of Object.keys(localStorage).filter((k) => k.startsWith(journalPrefix))) {
    const raw = localStorage.getItem(key)
    if (!raw) continue
    const entry = JSON.parse(raw) as { document: DraftDocument | null; id: string }
    if (entry.document) await database.put('documents', entry.document)
    else await database.delete('documents', entry.id)
    if (localStorage.getItem(key) === raw) localStorage.removeItem(key)
  }
  const tx = database.transaction(['documents', 'meta'], 'readwrite')
  if (!(await tx.objectStore('meta').get('initialized'))) {
    for (const document of createExamples()) await tx.objectStore('documents').put(document)
    await tx.objectStore('meta').put(true, 'initialized')
  }
  const documents = await tx.objectStore('documents').getAll()
  await tx.done
  return documents
}
export function persistDocument(document: DraftDocument | null, id = document?.id): Promise<void> {
  if (!id) return Promise.reject(new Error('Documento sem identificação.'))
  const raw = JSON.stringify({ document, id })
  const key = journalPrefix + id
  // Synchronous journal protects the latest keystroke during tab close.
  try {
    localStorage.setItem(key, raw)
  } catch {
    /* IndexedDB remains the primary store. */
  }
  const write = queue
    .catch(() => undefined)
    .then(async () => {
      const database = await db()
      if (document) await database.put('documents', document)
      else await database.delete('documents', id)
      try {
        if (localStorage.getItem(key) === raw) localStorage.removeItem(key)
      } catch {
        /* Primary write already succeeded. */
      }
    })
  queue = write
  return write
}
export function getPreference(key: string, fallback: string) {
  try {
    return localStorage.getItem(`draft:${key}`) ?? fallback
  } catch {
    return fallback
  }
}
export function setPreference(key: string, value: string) {
  try {
    localStorage.setItem(`draft:${key}`, value)
  } catch {
    /* Preferences must never prevent writing. */
  }
}
export async function readSyncState<T>(account: string): Promise<T | undefined> {
  return (await (await db()).get('meta', `drive:${account}`)) as T | undefined
}
export async function writeSyncState(account: string, value: unknown): Promise<void> {
  await (await db()).put('meta', value, `drive:${account}`)
}
