import { defineConfig } from '@playwright/test';
export default defineConfig({testDir:'./e2e',workers:1,use:{baseURL:process.env['EDAC_E2E_URL'] || 'http://127.0.0.1:8081',browserName:'chromium',viewport:{width:1440,height:1080},screenshot:'only-on-failure'},timeout:60000});
