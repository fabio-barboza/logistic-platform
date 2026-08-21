import { defineConfig } from 'vite'

export default defineConfig({
  root: '.',
  // .env centralizado na raiz do repo — ver .env.example lá.
  envDir: '..',
  build: {
    outDir: 'dist'
  }
})
