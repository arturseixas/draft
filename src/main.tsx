import { Component, type ReactNode } from 'react'
import { createRoot } from 'react-dom/client'
import '@fontsource/dm-sans/latin-400.css'
import '@fontsource/dm-sans/latin-500.css'
import '@fontsource/dm-sans/latin-600.css'
import '@fontsource/dm-sans/latin-700.css'
import '@fontsource/lora/latin-400.css'
import '@fontsource/lora/latin-400-italic.css'
import '@fontsource/lora/latin-500.css'
import '@fontsource/lora/latin-600.css'
import '@fontsource/ibm-plex-mono/latin-400.css'
import '@fontsource/ibm-plex-mono/latin-500.css'
import App from './App'
import './styles.css'

class ErrorBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false }
  static getDerivedStateFromError() {
    return { failed: true }
  }
  render() {
    if (this.state.failed)
      return (
        <div className="app-empty">
          <h1>Uma pausa inesperada.</h1>
          <p>
            Seus documentos salvos continuam neste navegador.
            <br />
            Reabra o Draft para continuar.
          </p>
          <button className="primary-button" onClick={() => location.reload()}>
            Reabrir o Draft
          </button>
        </div>
      )
    return this.props.children
  }
}
createRoot(document.getElementById('root')!).render(
  <ErrorBoundary>
    <App />
  </ErrorBoundary>,
)
