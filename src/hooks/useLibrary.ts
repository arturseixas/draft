import { useCallback, useEffect, useRef, useState } from 'react'
import { loadDocuments, persistDocument } from '../lib/storage'
import { emptyContent, type DraftDocument, type Mode } from '../types'
import { newId } from '../lib/id'
import { equalDocument, reconcileDocuments } from '../lib/drive/merge'
export function useLibrary() {
  const [documents, setDocuments] = useState<DraftDocument[]>([])
  const latest = useRef<DraftDocument[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState('')
  const [saveState, setSaveState] = useState<'saved' | 'saving' | 'error'>('saved')
  const pending = useRef(0)
  const failed = useRef(new Map<string, DraftDocument | null>())
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    loadDocuments()
      .then((docs) => {
        if (!mounted.current) return
        latest.current = docs
        setDocuments(docs)
        setLoading(false)
      })
      .catch(() => {
        setLoadError(
          'Não foi possível abrir seus documentos. Verifique se o navegador permite armazenamento local e tente novamente. Seus dados não foram substituídos.',
        )
        setLoading(false)
      })
    return () => {
      mounted.current = false
    }
  }, [])
  const save = useCallback((document: DraftDocument | null, id: string) => {
    pending.current++
    setSaveState('saving')
    return persistDocument(document, id)
      .then(() => {
        failed.current.delete(id)
        return true
      })
      .catch(() => {
        failed.current.set(id, document)
        return false
      })
      .finally(() => {
        pending.current--
        if (mounted.current)
          setSaveState(pending.current ? 'saving' : failed.current.size ? 'error' : 'saved')
      })
  }, [])
  useEffect(() => {
    const preventLoss = (event: BeforeUnloadEvent) => {
      if (failed.current.size || pending.current) {
        event.preventDefault()
        event.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', preventLoss)
    return () => window.removeEventListener('beforeunload', preventLoss)
  }, [])
  const commit = useCallback(
    (document: DraftDocument) => {
      const exists = latest.current.some((doc) => doc.id === document.id)
      latest.current = exists
        ? latest.current.map((doc) => (doc.id === document.id ? document : doc))
        : [...latest.current, document]
      setDocuments(latest.current)
      save(document, document.id)
      return document
    },
    [save],
  )
  const update = useCallback(
    (id: string, patch: Partial<DraftDocument>) => {
      const document = latest.current.find((doc) => doc.id === id)
      if (document) commit({ ...document, ...patch, id, updatedAt: Date.now() })
    },
    [commit],
  )
  const create = useCallback(
    (title: string, mode: Mode) =>
      commit({
        id: newId(),
        title: title.trim() || 'Sem título',
        mode,
        favorite: false,
        content: emptyContent(mode),
        createdAt: Date.now(),
        updatedAt: Date.now(),
      }),
    [commit],
  )
  const duplicate = useCallback(
    (id: string) => {
      const doc = latest.current.find((item) => item.id === id)
      if (!doc) return
      return commit({
        ...structuredClone(doc),
        id: newId(),
        title: `${doc.title} (cópia)`,
        favorite: false,
        createdAt: Date.now(),
        updatedAt: Date.now(),
      })
    },
    [commit],
  )
  const remove = useCallback(
    (id: string) => {
      latest.current = latest.current.filter((doc) => doc.id !== id)
      setDocuments(latest.current)
      save(null, id)
    },
    [save],
  )
  const retry = useCallback(() => {
    for (const [id, doc] of failed.current) save(doc, id)
  }, [save])
  const getDocuments = useCallback(() => latest.current, [])
  const applyRemote = useCallback(
    async (expected: DraftDocument[], incoming: DraftDocument[]) => {
      const previous = latest.current
      const result = reconcileDocuments(previous, expected, incoming)
      const before = new Map(previous.map((doc) => [doc.id, doc]))
      const after = new Map(result.documents.map((doc) => [doc.id, doc]))
      const changed = [...new Set([...before.keys(), ...after.keys()])].filter(
        (id) => !equalDocument(before.get(id), after.get(id)),
      )
      if (changed.length) {
        latest.current = result.documents
        setDocuments(result.documents)
      }
      const writes = changed.map((id) => save(after.get(id) ?? null, id))
      if ((await Promise.all(writes)).some((ok) => !ok))
        throw new Error(
          'Não foi possível salvar os documentos recebidos neste navegador. Libere espaço e tente novamente.',
        )
      // Also protect edits that happened during the IndexedDB writes.
      const current = new Map(latest.current.map((doc) => [doc.id, doc]))
      for (const id of new Set([...after.keys(), ...current.keys()]))
        if (!equalDocument(after.get(id), current.get(id))) result.deferred.add(id)
      return result.deferred
    },
    [save],
  )
  return {
    documents,
    loading,
    loadError,
    saveState,
    create,
    update,
    duplicate,
    remove,
    restore: commit,
    retry,
    getDocuments,
    applyRemote,
  }
}
