const scope = 'https://www.googleapis.com/auth/drive.appdata'
interface TokenResponse {
  access_token?: string
  expires_in?: number
  scope?: string
  error?: string
}
interface TokenClient {
  requestAccessToken: (options: { prompt: string }) => void
}
interface GoogleIdentity {
  accounts: {
    oauth2: {
      initTokenClient: (options: {
        client_id: string
        scope: string
        include_granted_scopes: boolean
        callback: (response: TokenResponse) => void
        error_callback: (error: { type: string }) => void
      }) => TokenClient
      revoke: (token: string, done: () => void) => void
    }
  }
}
declare global {
  interface Window {
    google?: GoogleIdentity
  }
}
export interface DriveSession {
  token: string
  expiresAt: number
  accountId: string
  email: string
}
let loading: Promise<void> | undefined
export function loadGoogleIdentity(): Promise<void> {
  if (window.google?.accounts.oauth2) return Promise.resolve()
  return (loading ??= new Promise<void>((resolve, reject) => {
    const script = document.createElement('script')
    script.src = 'https://accounts.google.com/gsi/client'
    script.async = true
    const timer = window.setTimeout(() => {
      script.remove()
      loading = undefined
      reject(new Error('O Google não respondeu. Confira sua conexão e tente novamente.'))
    }, 15000)
    script.onload = () => {
      clearTimeout(timer)
      resolve()
    }
    script.onerror = () => {
      clearTimeout(timer)
      script.remove()
      loading = undefined
      reject(
        new Error(
          'Não foi possível carregar o Google. Confira a conexão e os bloqueadores do navegador.',
        ),
      )
    }
    document.head.appendChild(script)
  }))
}
export function authorizeDrive(clientId: string): Promise<DriveSession> {
  // Called directly from the click handler, after the GIS script has loaded.
  return new Promise((resolve, reject) => {
    if (!window.google)
      return reject(new Error('A conexão com o Google ainda está sendo preparada.'))
    const client = window.google.accounts.oauth2.initTokenClient({
      client_id: clientId,
      scope: `${scope} openid email`,
      include_granted_scopes: false,
      error_callback: (error) =>
        reject(
          new Error(
            error.type === 'popup_closed'
              ? 'A janela do Google foi fechada. Seus textos continuam salvos localmente.'
              : 'Permita pop-ups para conectar ao Google Drive.',
          ),
        ),
      callback: async (response) => {
        if (!response.access_token || response.error) {
          reject(
            new Error('A conexão não foi autorizada. Você pode continuar escrevendo localmente.'),
          )
          return
        }
        if (!response.scope?.split(' ').includes(scope)) {
          reject(new Error('Autorize o armazenamento dos documentos do Draft para sincronizar.'))
          return
        }
        try {
          const result = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', {
            headers: { Authorization: `Bearer ${response.access_token}` },
            signal: AbortSignal.timeout(15000),
          })
          if (!result.ok)
            throw new Error(
              'Não foi possível identificar a conta Google. Tente conectar novamente.',
            )
          const user = (await result.json()) as { sub?: string; email?: string }
          if (!user.sub || !user.email)
            throw new Error('O Google não disponibilizou a identificação da conta.')
          resolve({
            token: response.access_token,
            expiresAt: Date.now() + (Number(response.expires_in) || 3600) * 1000 - 30000,
            accountId: user.sub,
            email: user.email,
          })
        } catch (error) {
          reject(error)
        }
      },
    })
    client.requestAccessToken({ prompt: 'select_account' })
  })
}
export class DriveAuthError extends Error {}
export async function driveFetch(session: DriveSession, url: string, init: RequestInit = {}) {
  if (Date.now() >= session.expiresAt)
    throw new DriveAuthError(
      'A autorização do Google expirou. Reconecte para enviar as alterações pendentes.',
    )
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${session.token}`)
  const response = await fetch(url, { ...init, headers, signal: AbortSignal.timeout(30000) })
  if (response.status === 401)
    throw new DriveAuthError('Reconecte sua conta Google para continuar a sincronização.')
  if (!response.ok) {
    if (response.status === 403)
      throw new Error(
        'O Drive recusou o acesso. Confira se a API Google Drive está ativada no projeto e se há espaço disponível na conta.',
      )
    if (response.status === 429 || response.status >= 500)
      throw new Error(
        'O Google Drive está temporariamente indisponível. As alterações locais serão reenviadas na próxima sincronização.',
      )
    throw new Error(
      `Não foi possível sincronizar com o Google Drive (${response.status}). Seus textos locais foram mantidos.`,
    )
  }
  return response
}
