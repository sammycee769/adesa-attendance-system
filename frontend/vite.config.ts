/// <reference types="vitest/config" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * The development server proxies `/api` to the Spring Boot backend. Production
 * uses VITE_API_BASE_URL; the backend permits only APP_PUBLIC_URL through CORS.
 */
export default defineConfig({
  plugins: [react()],
  server: {
    // Local development only. The Docker "frontend" profile overrides this
    // with an explicit --host, and its published port is bound to loopback.
    host: '127.0.0.1',
    port: 5173,
    allowedHosts: ['localhost', '127.0.0.1'],
    proxy: {
      '/api': {
        target: process.env.BACKEND_URL ?? 'http://localhost:8080',
        changeOrigin: false,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    css: false,
    restoreMocks: true,
    testTimeout: 15000,
    hookTimeout: 15000,
  },
});
