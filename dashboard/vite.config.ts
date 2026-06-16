/// <reference types="vitest/config" />
import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5174,
    proxy: { "/api": "http://localhost:8091" },
  },
  test: {
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
