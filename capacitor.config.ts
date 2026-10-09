import type { CapacitorConfig } from '@capacitor/cli';

const config: CapacitorConfig = {
  appId: 'com.harmony.mobile',
  appName: 'Harmony',
  webDir: 'dist',
  plugins: {
    // The shell runs at its own origin but probes each instance's
    // `GET /api/v1/meta`, which is cross-origin, and Harmony sets no CORS
    // headers. CapacitorHttp routes `fetch` through native HTTP so that works.
    CapacitorHttp: {
      enabled: true,
    },
  },
};

export default config;
