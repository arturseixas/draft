# Draft

Um espaço local de escrita criativa, com editores de texto livre, roteiro de filme e roteiro de vídeo. Interface em português, adaptada para celular e desktop, com temas claro e escuro.

## Executar

Requer Node.js 22.12+ (testado com Node.js 24).

```sh
npm install
npm run dev
```

Abra http://localhost:5173. Não é necessário configurar variáveis de ambiente, login, API ou banco de dados externo.

## Produção

```sh
npm run build
npm run preview
```

A pasta `dist/` pode ser publicada em qualquer hospedagem estática usando HTTPS. O service worker é gerado no build de produção e mantém o aplicativo, as fontes e os demais recursos disponíveis offline após o primeiro acesso. A atualização de uma versão instalada ocorre depois que as abas antigas do aplicativo forem fechadas. O app pode ser instalado pelo menu do navegador em dispositivos compatíveis.

## Escrita

- **Livre:** headings, listas numeradas e com marcadores, checklists aninhadas, citações, divisores, links, código, callouts, negrito, itálico e destaque. Markdown ao digitar, menu `/`, arraste de blocos no desktop e movimentação pelo menu no celular.
- **Filme:** cena, ação, personagem, diálogo, parêntese, transição e nota. Enter avança conforme o contexto; Tab e Shift+Tab alternam tipos. Cabeçalhos INT./EXT. e nomes curtos em maiúsculas são reconhecidos ao pressionar Enter.
- **Vídeo:** narração, imagem/B-roll, texto na tela, música, som e observações de edição, com hierarquia visual de blocos.
- O formato pode ser trocado preservando o texto e a formatação compatível. A notificação permite desfazer a conversão imediatamente.
- Renomear pelo título ou pelo menu, duplicar, buscar por título ou conteúdo, favoritar, excluir com confirmação e desfazer a exclusão.

## Dados e salvamento

O IndexedDB (`draft-writing`, versão 1) guarda os documentos individualmente. Cada alteração é primeiro registrada em um diário síncrono no LocalStorage; as transações IndexedDB são processadas em sequência. Quando a transação termina, o registro de recuperação correspondente é removido. Na próxima abertura, alterações interrompidas são recuperadas antes de mostrar os documentos. Falhas de gravação ficam visíveis, com opção de tentar novamente e exportar. Os exemplos são criados apenas na primeira inicialização, inclusive quando todos os documentos são posteriormente excluídos.

Os dados pertencem ao navegador e à origem (protocolo, domínio e porta). Não há sincronização de nuvem. Evite editar o mesmo documento simultaneamente em várias abas. Para trocar de navegador, dispositivo ou endereço de hospedagem, exporte o backup JSON pelo botão **Salvo neste dispositivo**. A importação adiciona cópias sem substituir documentos existentes. Limpar os dados do navegador remove a biblioteca local.

## Exportação

- Markdown (`.md`) com hierarquia, marcas, listas, checklists e identificação dos blocos audiovisuais.
- Texto (`.txt`) com UTF-8 e indentação legível para roteiro cinematográfico.
- **PDF / imprimir** abre a impressão nativa do navegador; escolha **Salvar como PDF**. O layout A4 esconde a interface e aplica margens e tipografia próprias para o roteiro. Numeração e cabeçalhos adicionais dependem das opções da impressão do navegador.
- Backup JSON completo, incluindo datas e favoritos.

## Atalhos

| Ação               | Windows/Linux             | macOS             |
| ------------------ | ------------------------- | ----------------- |
| Buscar             | Ctrl+K                    | ⌘K                |
| Novo documento     | Ctrl+Alt+N                | ⌘⌥N               |
| Modo foco          | Ctrl+Shift+F              | ⌘⇧F               |
| Sair do foco       | Esc                       | Esc               |
| Negrito / itálico  | Ctrl+B / Ctrl+I           | ⌘B / ⌘I           |
| Desfazer / refazer | Ctrl+Z / Ctrl+Shift+Z     | ⌘Z / ⌘⇧Z          |
| Tipos de roteiro   | Tab / Shift+Tab / Alt+1…7 | Tab / ⇧Tab / ⌥1…7 |
| Mover bloco        | Ctrl+Alt+↑ / ↓            | ⌘⌥↑ / ↓           |
| Ajuda              | Ctrl+/                    | ⌘/                |

## Arquitetura

- React 19, TypeScript, Vite e CSS com tokens compartilhados.
- Tiptap/ProseMirror para a edição e o histórico; extensões próprias em `src/editor/extensions.ts`.
- Radix Dialog e Dropdown Menu para foco, navegação de teclado e modais acessíveis.
- Fontes incluídas no aplicativo e ícones Phosphor, sem recursos de rede obrigatórios.
- `src/hooks/useLibrary.ts`: operações e estado da biblioteca.
- `src/lib/storage.ts`: persistência, recuperação e preferências.
- `src/lib/export.ts`: serialização e backups.
- Componentes de navegação, editor e diálogos em `src/components/`.

## Verificação

Com o servidor em execução:

```sh
npm test
```

Os testes Playwright verificam os fluxos em desktop e viewport móvel. A configuração usa o Microsoft Edge instalado (`channel: 'msedge'`); em outro ambiente, instale-o com `npx playwright install msedge` ou adapte o canal para Chromium. `DRAFT_TEST_URL` permite testar o build de produção, por exemplo `http://localhost:4173`.

O conjunto cobre os três formatos, atalhos, slash commands, formatação, links, checklists, arraste/movimentação, busca, favoritos, duplicação, exclusão e restauração, exportação, backups, recuperação de escrita interrompida, fechamento/reabertura, temas, foco e layout para impressão. A emulação móvel não substitui testes em todos os teclados e dispositivos físicos.
