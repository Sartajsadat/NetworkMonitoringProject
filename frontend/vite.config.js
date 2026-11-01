import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

// In development the Vite server proxies API calls to the Spring Boot backend so the
// browser sees a single origin (same as production behind nginx): no CORS needed.
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '');
  const backend = env.VITE_BACKEND_URL || 'http://localhost:8080';
  return {
    plugins: [react()],
    server: {
      port: 5173,
      proxy: {
        '/api': { target: backend, changeOrigin: false },
        '/actuator': { target: backend, changeOrigin: false },
      },
    },
    build: { outDir: 'dist', sourcemap: false, chunkSizeWarningLimit: 1000 },
    test: {
      environment: 'jsdom',
      globals: true,
      setupFiles: ['./src/test/setup.js'],
      css: false,
    },
  };
});
