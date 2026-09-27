import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = path.dirname(fileURLToPath(import.meta.url))

// Air Defence cho JLAS Game Arena: build thẳng vào static/arena/app/air-defense của Spring Boot.
// Trang Thymeleaf air-defense/board.html nạp air-defense-app.css + air-defense-app.js.
export default defineConfig({
  base: '/arena/app/air-defense/',
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(projectRoot, './src'),
    },
  },
  build: {
    outDir: '../../src/main/resources/static/arena/app/air-defense',
    emptyOutDir: true,
    cssCodeSplit: false,
    rollupOptions: {
      input: path.resolve(projectRoot, 'src/main.tsx'),
      output: {
        entryFileNames: 'air-defense-app.js',
        chunkFileNames: 'chunks/[name]-[hash].js',
        assetFileNames: (assetInfo) => {
          const names = [...(assetInfo.names ?? []), ...(assetInfo.name ? [assetInfo.name] : [])]
          return names.some((name) => name.endsWith('.css'))
            ? 'air-defense-app.css'
            : 'assets/[name]-[hash][extname]'
        },
      },
    },
  },
  server: {
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
