import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';
import { fileURLToPath } from 'node:url';

// Built into the Spring Boot static folder and served at /arena/app/neko/ by the Thymeleaf host page
// (templates/neko/board.html), which loads neko-app.css + neko-app.js and provides #neko-root.
const OUT_DIR = '../../src/main/resources/static/arena/app/neko';

export default defineConfig(({ command }) => ({
  base: '/arena/app/neko/',
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: { '@neko-static': fileURLToPath(new URL(OUT_DIR, import.meta.url)) },
  },
  // Sprites/sounds live only once, next to the bundle in the Spring static folder (sprites/, sound/, …):
  // the dev server serves them from there, and a build only rewrites neko-app.js/.css, so the folder
  // must not be emptied. Add new art or sounds directly under OUT_DIR.
  publicDir: command === 'serve' ? OUT_DIR : false,
  build: {
    outDir: OUT_DIR,
    emptyOutDir: false,
    cssCodeSplit: false,
    rollupOptions: {
      input: 'src/main.tsx',
      output: {
        entryFileNames: 'neko-app.js',
        assetFileNames: (assetInfo) => {
          const names = assetInfo.names ?? (assetInfo.name ? [assetInfo.name] : []);
          return names.some((name) => name.endsWith('.css'))
            ? 'neko-app.css'
            : 'assets/[name]-[hash][extname]';
        },
      },
    },
  },
  server: {
    port: 3000,
    host: true,
    // Local dev against a running Spring Boot app (npm run dev, then open /arena/app/neko/?sid=…).
    proxy: {
      '/api': 'http://localhost:8080',
      '/js': 'http://localhost:8080',
      '/arena/js': 'http://localhost:8080',
    },
  },
}));
