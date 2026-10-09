import { defineConfig } from 'vite';

export default defineConfig({
  // Capacitor serves the built app from its own origin; relative asset paths
  // keep it working there and from a plain `file://` preview.
  base: './',
  build: {
    outDir: 'dist',
    target: 'es2022',
  },
  server: {
    host: '127.0.0.1',
    port: 5180,
  },
});
