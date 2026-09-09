import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { VitePWA } from 'vite-plugin-pwa'

export default defineConfig({
  plugins: [
    react(),
    VitePWA({
      registerType: 'prompt',
      includeAssets: ['favicon.svg'],
      manifest: {
        name: 'Draft — seu espaço de escrita',
        short_name: 'Draft',
        description: 'Histórias, roteiros e ideias. Seu caderno de escrita local.',
        theme_color: '#fafaf8',
        background_color: '#fafaf8',
        display: 'standalone',
        lang: 'pt-BR',
        start_url: '/',
        icons: [
          { src: '/icon-192.png', sizes: '192x192', type: 'image/png' },
          { src: '/icon-512.png', sizes: '512x512', type: 'image/png' },
        ],
      },
      workbox: {
        clientsClaim: true,
        globPatterns: ['**/*.{js,css,html,svg,png,woff2}'],
        maximumFileSizeToCacheInBytes: 4000000,
      },
    }),
  ],
  server: { port: 5173 },
  build: {
    rolldownOptions: {
      output: {
        codeSplitting: {
          groups: [
            { name: 'document-engine', test: /node_modules[\\/]prosemirror-/, priority: 20 },
            { name: 'editor-engine', test: /node_modules[\\/](?:@tiptap|prosemirror)[\\/-]/ },
          ],
        },
      },
    },
  },
})
