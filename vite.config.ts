import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  // Relative asset URLs allow the same build to run at a domain root or a
  // GitHub Pages repository path such as /guitar-tuner-pwa/.
  base: './',
  plugins: [react()],
  server: {
    host: true,
  },
  preview: {
    host: true,
  },
})
