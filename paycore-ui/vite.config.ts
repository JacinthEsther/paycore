import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// In development the UI calls /api on its own origin and Vite forwards it
// to Spring Boot, so no CORS setup is needed locally. PAYCORE_API_URL
// changes where it forwards to. In production set VITE_API_BASE_URL to the
// API's origin instead (see README.md).
const api = process.env.PAYCORE_API_URL ?? 'http://localhost:8080';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': api,
      '/actuator': api,
    },
  },
});
