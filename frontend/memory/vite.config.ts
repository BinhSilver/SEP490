import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { cpSync, existsSync, mkdirSync, copyFileSync } from 'node:fs'
import { resolve } from 'node:path'

const memoryOutput = resolve(__dirname, '../../src/main/resources/static/arena/app/memory')
const springClasses = resolve(__dirname, '../../target/classes')

// Bundle được build thẳng vào static của Spring; trang host là templates/memory/board.html.
export default defineConfig({
  plugins: [react(), {
    name: 'sync-memory-spring-resources',
    apply: 'build',
    writeBundle() {
      // A running Spring app serves target/classes; rebuilding Vite must update that copy too.
      if (!existsSync(springClasses)) return
      cpSync(memoryOutput, resolve(springClasses, 'static/arena/app/memory'), { recursive: true })
      const templateDirectory = resolve(springClasses, 'templates/memory')
      mkdirSync(templateDirectory, { recursive: true })
      copyFileSync(resolve(__dirname, '../../src/main/resources/templates/memory/board.html'),
        resolve(templateDirectory, 'board.html'))
    }
  }],
  base: '/arena/app/memory/',
  server: {
    proxy: { '/api': 'http://localhost:8080' }
  },
  build: {
    outDir: memoryOutput,
    // Keep stable asset paths in place so IDE resource watchers do not delete the running copy.
    emptyOutDir: false,
    cssCodeSplit: false,
    rollupOptions: {
      input: resolve(__dirname, 'src/main.tsx'),
      output: {
        entryFileNames: 'memory-app.js',
        chunkFileNames: 'chunks/[name]-[hash].js',
        assetFileNames: (assetInfo) =>
          assetInfo.names?.some((name) => name.endsWith('.css')) ? 'memory-app.css' : 'assets/[name]-[hash][extname]'
      }
    }
  }
})
