import { cpSync } from 'node:fs';
import { resolve } from 'node:path';
import { defineConfig } from 'vite';

export default defineConfig({
  base: './',
  build: { rolldownOptions: { output: { codeSplitting: {
    groups: [{ name: 'vendor', test: /node_modules/ }],
  } } } },
  // Production nginx proxies /api/ to the game server's localhost endpoint.
  server: { watch: { ignored: ['**/nodes/**'] }, proxy: { '/api': 'http://127.0.0.1:8125' } },
  plugins: [{
    // Keep the live data feed at its existing path. Vite serves it directly in
    // development; copy a snapshot for static hosting without bundling JSON.
    name: 'nodes-data',
    writeBundle({ dir }) { cpSync('nodes', resolve(dir, 'nodes'), { recursive: true }); },
  }],
});
