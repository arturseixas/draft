import fs from 'node:fs'
import path from 'node:path'
function visit(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const filename = path.join(directory, entry.name)
    if (entry.isDirectory()) visit(filename)
    else if (filename.endsWith('.tsx')) {
      const source = fs.readFileSync(filename, 'utf8')
      const optimized = source.replace(/import \{ ([^}]+) \} from '@phosphor-icons\/react'/g, (_, symbols) => symbols.split(',').map(symbol => symbol.trim()).map(symbol => `import { ${symbol} } from '@phosphor-icons/react/dist/csr/${symbol}'`).join('\n'))
      if (source !== optimized) fs.writeFileSync(filename, optimized)
    }
  }
}
visit('src')
