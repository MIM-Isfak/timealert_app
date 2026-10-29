const { spawnSync } = require('node:child_process');

try {
  const url = new URL(process.env.REACT_APP_API_URL);
  if (url.protocol !== 'https:' || url.username || url.password || url.search || url.hash ||
      url.pathname !== '/api' || ['localhost', '127.0.0.1'].includes(url.hostname)) throw new Error();
} catch {
  console.error('Set REACT_APP_API_URL to the final HTTPS Render API URL ending in /api.');
  process.exit(1);
}
const result = spawnSync(process.execPath, [require.resolve('react-scripts/scripts/build')], {
  stdio: 'inherit', env: { ...process.env, GENERATE_SOURCEMAP: 'false' },
});
process.exit(result.status ?? 1);
