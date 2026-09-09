import { useEffect, useState } from 'react'
import { CloudArrowUp } from '@phosphor-icons/react/dist/csr/CloudArrowUp'
import { ArrowsClockwise } from '@phosphor-icons/react/dist/csr/ArrowsClockwise'
import { Modal } from './ui'
import { loadGoogleIdentity } from '../lib/drive/google'
import type { useDrive } from '../hooks/useDrive'
export function DriveDialog({
  open,
  onOpenChange,
  drive,
}: {
  open: boolean
  onOpenChange: (value: boolean) => void
  drive: ReturnType<typeof useDrive>
}) {
  const [ready, setReady] = useState(false)
  const [sdkError, setSdkError] = useState('')
  const [config, setConfig] = useState(drive.clientId)
  const [editing, setEditing] = useState(!drive.clientId)
  const [configError, setConfigError] = useState('')
  const prepare = () => {
    setSdkError('')
    void loadGoogleIdentity()
      .then(() => setReady(true))
      .catch((reason) => setSdkError(reason.message))
  }
  useEffect(() => {
    if (open && drive.clientId) prepare()
  }, [open, drive.clientId])
  const busy = drive.status === 'syncing' || drive.status === 'connecting'
  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title="Suas palavras, em todo lugar."
      description="Conecte seu Google Drive para continuar a mesma história no computador e no celular."
      className="drive-modal"
    >
      <div className="backup-summary">
        <CloudArrowUp size={28} />
        <div>
          <strong>{drive.email || 'Google Drive'}</strong>
          <span>
            {drive.status === 'syncing'
              ? 'Sincronizando documentos…'
              : drive.lastSync
                ? `Última sincronização às ${new Date(drive.lastSync).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })}`
                : 'O salvamento local continua sempre ativo.'}
          </span>
        </div>
      </div>
      <p className="backup-note">
        Ao conectar, os documentos deste espaço serão enviados à conta escolhida. O Draft acessa
        apenas sua própria área de dados no Drive. Seus outros arquivos não são acessados.
      </p>
      {drive.error && (
        <p className="error-text" role="alert">
          {drive.error}
        </p>
      )}
      {drive.conflicts > 0 && (
        <p className="drive-conflict" role="status">
          Preservamos {drive.conflicts} versão(ões) diferente(s) em documentos com “(conflito)” no
          nome. Compare as versões antes de excluir qualquer uma.
        </p>
      )}
      {editing ? (
        <form
          className="drive-config"
          onSubmit={(event) => {
            event.preventDefault()
            if (!/^\d+-[a-zA-Z0-9_-]+\.apps\.googleusercontent\.com$/.test(config.trim())) {
              setConfigError(
                'Cole o Client ID de um aplicativo da Web, terminado em .apps.googleusercontent.com.',
              )
              return
            }
            drive.configure(config)
            setEditing(false)
            setConfigError('')
          }}
        >
          <label className="field-label" htmlFor="google-client">
            Client ID do Google
          </label>
          <input
            id="google-client"
            className="text-input"
            value={config}
            onChange={(event) => setConfig(event.target.value)}
            autoComplete="off"
            spellCheck={false}
            aria-describedby="client-id-help"
          />
          <p id="client-id-help" className="backup-note">
            É um identificador público do aplicativo. Não cole um Client Secret aqui.
          </p>
          {configError && (
            <p className="error-text" role="alert">
              {configError}
            </p>
          )}
          <div className="modal-actions">
            <button className="primary-button" type="submit">
              Salvar configuração
            </button>
          </div>
        </form>
      ) : (
        <div className="backup-actions">
          {drive.connected ? (
            <>
              <button className="primary-button" disabled={busy} onClick={() => void drive.sync()}>
                <ArrowsClockwise size={17} />
                {busy ? 'Sincronizando…' : 'Sincronizar agora'}
              </button>
              <button className="secondary-button" disabled={busy} onClick={drive.disconnect}>
                Desconectar
              </button>
            </>
          ) : (
            <button className="primary-button" disabled={!ready || busy} onClick={drive.connect}>
              <CloudArrowUp size={18} />
              {busy
                ? 'Aguardando o Google…'
                : !ready
                  ? 'Preparando conexão…'
                  : drive.email
                    ? 'Reconectar ao Google Drive'
                    : 'Conectar ao Google Drive'}
            </button>
          )}
        </div>
      )}
      {sdkError && (
        <p className="error-text" role="alert">
          {sdkError}{' '}
          <button className="text-button" onClick={prepare}>
            Tentar novamente
          </button>
        </p>
      )}
      {!editing && !drive.connected && !busy && (
        <button
          className="text-button drive-settings"
          onClick={() => {
            setConfig(drive.clientId)
            setEditing(true)
          }}
        >
          Alterar Client ID
        </button>
      )}
      <details className="drive-help">
        <summary>Como criar meu Client ID gratuito</summary>
        <ol>
          <li>
            Abra o{' '}
            <a href="https://console.cloud.google.com/" target="_blank" rel="noopener noreferrer">
              Google Cloud Console
            </a>{' '}
            e crie um projeto.
          </li>
          <li>
            Ative a <strong>Google Drive API</strong> na biblioteca de APIs.
          </li>
          <li>
            Em <strong>Google Auth Platform</strong>, preencha os dados do aplicativo. Em modo de
            teste, adicione seu e-mail em <strong>Público-alvo → Usuários de teste</strong>.
          </li>
          <li>
            Em <strong>Clientes</strong>, crie um cliente OAuth do tipo{' '}
            <strong>Aplicativo da Web</strong>.
          </li>
          <li>
            Adicione <code>{window.location.origin}</code> às origens JavaScript autorizadas. Para
            desenvolvimento, adicione também <code>http://localhost:5173</code>. Não é necessário
            URI de redirecionamento para este fluxo.
          </li>
          <li>
            Copie o Client ID e salve-o no campo acima. Use o mesmo Client ID nos outros
            dispositivos, com o endereço HTTPS do site autorizado.
          </li>
        </ol>
      </details>
      <p className="backup-note">
        A sincronização acontece com o Draft aberto. Ao reabrir o app ou quando a autorização
        expirar, toque em Reconectar. As revisões anteriores ficam guardadas para proteger
        alterações simultâneas e ocupam espaço na conta. Desconectar mantém os documentos locais e
        os do Drive.
      </p>
    </Modal>
  )
}
