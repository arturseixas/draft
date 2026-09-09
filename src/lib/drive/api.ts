import { parseBackup } from '../export'
import type { Revision } from './merge'
import { driveFetch, type DriveSession } from './google'
const base = 'https://www.googleapis.com/drive/v3/files'
interface DriveFile {
  id: string
  name: string
}

export async function listRevisions(session: DriveSession): Promise<DriveFile[]> {
  const files: DriveFile[] = []
  let pageToken: string | undefined
  do {
    const params = new URLSearchParams({
      spaces: 'appDataFolder',
      q: "trashed = false and appProperties has { key='draftFormat' and value='revision-v1' }",
      fields: 'nextPageToken,files(id,name)',
      pageSize: '1000',
    })
    if (pageToken) params.set('pageToken', pageToken)
    const response = await driveFetch(session, `${base}?${params}`)
    const data = (await response.json()) as { files?: DriveFile[]; nextPageToken?: string }
    files.push(...(data.files ?? []))
    pageToken = data.nextPageToken
  } while (pageToken)
  return files
}
export function validateRevision(value: unknown): Revision {
  const r = value as Revision
  if (
    !r ||
    r.version !== 1 ||
    typeof r.id !== 'string' ||
    !/^[a-zA-Z0-9_-]{1,160}$/.test(r.id) ||
    typeof r.documentId !== 'string' ||
    r.documentId.length > 250 ||
    !Array.isArray(r.parents) ||
    r.parents.some((id) => typeof id !== 'string') ||
    !('document' in r)
  )
    throw new Error(
      'Um arquivo de sincronização do Draft está inválido. Nenhum documento foi substituído.',
    )
  if (r.document !== null) {
    parseBackup(JSON.stringify({ version: 1, documents: [r.document] }))
    if (
      r.document.id !== r.documentId ||
      !Number.isFinite(r.document.updatedAt) ||
      !Number.isFinite(r.document.createdAt)
    )
      throw new Error('A identificação de um documento do Drive está inválida.')
  }
  return r
}
export async function readRevision(session: DriveSession, file: DriveFile) {
  const response = await driveFetch(session, `${base}/${encodeURIComponent(file.id)}?alt=media`)
  const body = await response.text()
  if (body.length > 10_000_000)
    throw new Error('Um documento do Drive ultrapassou o limite de 10 MB por revisão.')
  const revision = validateRevision(JSON.parse(body))
  if (file.name !== `draft-revision-${revision.id}.json`)
    throw new Error('Um arquivo do Drive não corresponde à revisão esperada.')
  return revision
}
export async function uploadRevision(session: DriveSession, revision: Revision) {
  const boundary = `draft_${crypto.randomUUID()}`
  const metadata = {
    name: `draft-revision-${revision.id}.json`,
    parents: ['appDataFolder'],
    mimeType: 'application/json',
    appProperties: { draftFormat: 'revision-v1' },
  }
  const body = `--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(metadata)}\r\n--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(revision)}\r\n--${boundary}--`
  await driveFetch(
    session,
    'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id',
    {
      method: 'POST',
      headers: { 'Content-Type': `multipart/related; boundary=${boundary}` },
      body,
    },
  )
}
