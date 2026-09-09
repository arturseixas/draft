import { useCallback, useEffect, useRef, useState } from 'react'
import type { useLibrary } from './useLibrary'
import { getPreference, setPreference } from '../lib/storage'
import { authorizeDrive, DriveAuthError, type DriveSession } from '../lib/drive/google'
import { synchronizeDrive } from '../lib/drive/sync'
import { GOOGLE_CLIENT_ID } from '../lib/drive/config'

export type DriveStatus =
  'disconnected' | 'connecting' | 'pending' | 'syncing' | 'synced' | 'error' | 'reconnect'
export function useDrive(library: ReturnType<typeof useLibrary>) {
  const [clientId, setClientId] = useState(() => getPreference('google-client-id', GOOGLE_CLIENT_ID))
  const [email, setEmail] = useState(() => getPreference('google-email', ''))
  const [status, setStatus] = useState<DriveStatus>(() =>
    getPreference('google-email', '') ? 'reconnect' : 'disconnected',
  )
  const [error, setError] = useState('')
  const [lastSync, setLastSync] = useState<number | null>(null)
  const [conflicts, setConflicts] = useState(0)
  const session = useRef<DriveSession | null>(null)
  const busy = useRef(false)
  const latest = useRef(library)
  latest.current = library
  const connected = status !== 'disconnected' && status !== 'reconnect' && status !== 'connecting'
  const sync = useCallback(async () => {
    const active = session.current
    if (!active || busy.current || latest.current.loading || latest.current.loadError) return
    if (!navigator.onLine) {
      setStatus('pending')
      setError('Sem conexão. Suas alterações continuam salvas neste dispositivo.')
      return
    }
    if (latest.current.saveState === 'error') {
      setStatus('error')
      setError('Resolva a falha de salvamento local antes de sincronizar.')
      return
    }
    busy.current = true
    setStatus('syncing')
    setError('')
    try {
      const result = await synchronizeDrive(
        active,
        `${clientId}:${active.accountId}`,
        latest.current.getDocuments,
        latest.current.applyRemote,
      )
      setLastSync(Date.now())
      setConflicts(result.conflicts)
      setStatus(result.deferred ? 'pending' : 'synced')
    } catch (reason) {
      if (reason instanceof DriveAuthError) {
        session.current = null
        setStatus('reconnect')
      } else setStatus('error')
      setError(
        reason instanceof Error
          ? reason.message
          : 'A sincronização falhou. Seus documentos locais estão preservados.',
      )
    } finally {
      busy.current = false
    }
  }, [clientId])
  const connect = () => {
    setStatus('connecting')
    setError('')
    void authorizeDrive(clientId)
      .then((active) => {
        session.current = active
        setEmail(active.email)
        setPreference('google-email', active.email)
        setStatus('pending')
        void sync()
      })
      .catch((reason) => {
        setStatus(email ? 'reconnect' : 'disconnected')
        setError(reason instanceof Error ? reason.message : 'Não foi possível conectar ao Google.')
      })
  }
  useEffect(() => {
    if (!session.current) return
    if (!busy.current) setStatus('pending')
    const timer = setTimeout(() => void sync(), 4000)
    return () => clearTimeout(timer)
  }, [library.documents, sync])
  useEffect(() => {
    const timer = setInterval(() => void sync(), 60000)
    const onReturn = () => {
      if (document.visibilityState === 'visible') void sync()
    }
    window.addEventListener('online', sync)
    document.addEventListener('visibilitychange', onReturn)
    return () => {
      clearInterval(timer)
      window.removeEventListener('online', sync)
      document.removeEventListener('visibilitychange', onReturn)
    }
  }, [sync])
  const configure = (value: string) => {
    setClientId(value.trim())
    setPreference('google-client-id', value.trim())
    session.current = null
    setEmail('')
    setPreference('google-email', '')
    setStatus('disconnected')
    setError('')
  }
  const disconnect = () => {
    session.current = null
    setEmail('')
    setPreference('google-email', '')
    setStatus('disconnected')
    setError('')
    setLastSync(null)
  }
  return {
    clientId,
    email,
    status,
    error,
    lastSync,
    conflicts,
    connected,
    connect,
    sync,
    configure,
    disconnect,
  }
}
