import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'node:path'

// Bundle được build thẳng vào static của Spring; trang host là templates/memory/board.html.
export default defineConfig({
  plugins: [react()],
  base: '/arena/app/memory/',
  server: {
    proxy: { '/api': 'http://localhost:8080' }
  },
  build: {
    outDir: resolve(__dirname, '../../src/main/resources/static/arena/app/memory'),
    emptyOutDir: true,
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
