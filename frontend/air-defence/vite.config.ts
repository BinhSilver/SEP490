import { defineConfig, type Plugin } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = path.dirname(fileURLToPath(import.meta.url))
const ENTRY_FILE = 'air-defense-app.js'

/**
 * board.html nạp file chính kèm ?v=…; chunk nào import "../air-defense-app.js" sẽ làm trình duyệt chạy file chính
 * lần hai và PixiJS hỏng ("Extension type texture-source already has a handler"). Dừng build thay vì để lỗi lọt ra.
 */
function entryIsNotImported(): Plugin {
  return {
    name: 'jlas-entry-is-not-imported',
    generateBundle(_options, bundle) {
      const offenders = Object.values(bundle)
        .filter((file) => file.type === 'chunk' && !file.isEntry)
        .filter((chunk) => chunk.type === 'chunk'
          && [...chunk.imports, ...chunk.dynamicImports].includes(ENTRY_FILE))
        .map((chunk) => chunk.fileName)
      if (offenders.length) {
        this.error(`${offenders.join(', ')} import ${ENTRY_FILE}; giữ code dùng chung ngoài src/main.tsx (xem bootstrap.tsx)`)
      }
    },
  }
}

// Air Defence cho JLAS Game Arena: build thẳng vào static/arena/app/air-defense của Spring Boot.
// Trang Thymeleaf air-defense/board.html nạp air-defense-app.css + air-defense-app.js.
export default defineConfig({
  base: '/arena/app/air-defense/',
  plugins: [react(), tailwindcss(), entryIsNotImported()],
  resolve: {
    dedupe: ['react', 'react-dom'],
    alias: {
      '@': path.resolve(projectRoot, './src'),
      '@neko-static': path.resolve(projectRoot, '../../src/main/resources/static/arena/app/neko'),
    },
  },
  build: {
    outDir: '../../src/main/resources/static/arena/app/air-defense',
    emptyOutDir: true,
    cssCodeSplit: false,
    rollupOptions: {
      input: path.resolve(projectRoot, 'src/main.tsx'),
      output: {
        entryFileNames: ENTRY_FILE,
        chunkFileNames: 'chunks/[name]-[hash].js',
        // Hàm tải trước (__vitePreload) mà Vite chèn cho mọi import() mặc định nằm trong file chính, nên chunk có
        // import() sẽ phải import ngược file chính (xem entryIsNotImported). Tách nó thành chunk riêng có hash.
        manualChunks: (id) => (id.includes('vite/preload-helper') ? 'preload-helper' : undefined),
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
