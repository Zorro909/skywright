import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createServer } from 'node:net';

const image = process.env['SKYWRIGHT_POSTGRESQL_IMAGE'];

if (!image) {
  throw new Error(
    'SKYWRIGHT_POSTGRESQL_IMAGE must contain the repository-pinned PostgreSQL image',
  );
}

const containerCli = process.env['SKYWRIGHT_CONTAINER_CLI'] ?? 'docker';
const containerName = `skywright-acceptance-${randomUUID()}`;
const adminPassword = randomUUID();
const migratorPassword = randomUUID();
const runtimePassword = randomUUID();
let containerStarted = false;

function run(command, args, options = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, {
      env: options.env ?? process.env,
      stdio: options.inherit ? 'inherit' : ['pipe', 'pipe', 'pipe'],
    });
    const stdout = [];
    const stderr = [];

    if (!options.inherit) {
      child.stdout.on('data', (chunk) => stdout.push(chunk));
      child.stderr.on('data', (chunk) => stderr.push(chunk));
      child.stdin.end(options.input);
    }

    child.on('error', reject);
    child.on('close', (code, signal) => {
      if (code === 0) {
        const output = options.includeStderr ? [...stdout, ...stderr] : stdout;
        resolve(Buffer.concat(output).toString('utf8').trim());
        return;
      }
      const detail = Buffer.concat(stderr).toString('utf8').trim();
      reject(
        new Error(
          `${command} ${args.join(' ')} failed (${signal ?? `exit ${code}`}): ${detail}`,
        ),
      );
    });
  });
}

const delay = (milliseconds) =>
  new Promise((resolve) => setTimeout(resolve, milliseconds));

function availablePort() {
  return new Promise((resolve, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      if (!address || typeof address === 'string') {
        server.close();
        reject(new Error('Could not allocate an acceptance port'));
        return;
      }
      server.close((error) =>
        error ? reject(error) : resolve(String(address.port)),
      );
    });
  });
}

async function waitForPostgreSql() {
  let lastFailure;
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      await run(containerCli, [
        'exec',
        containerName,
        'pg_isready',
        '--username',
        'postgres',
        '--dbname',
        'postgres',
      ]);
      return;
    } catch (error) {
      lastFailure = error;
      await delay(1_000);
    }
  }
  throw new Error('PostgreSQL did not become ready within 60 seconds', {
    cause: lastFailure,
  });
}

async function psql(database, sql) {
  await run(
    containerCli,
    [
      'exec',
      '--interactive',
      containerName,
      'psql',
      '--set',
      'ON_ERROR_STOP=1',
      '--username',
      'postgres',
      '--dbname',
      database,
    ],
    { input: sql },
  );
}

async function cleanup() {
  if (!containerStarted) return;
  containerStarted = false;
  await run(containerCli, ['rm', '--force', containerName]).catch((error) => {
    console.error(`Could not remove ${containerName}:`, error);
  });
}

for (const signal of ['SIGINT', 'SIGTERM']) {
  process.once(signal, async () => {
    await cleanup();
    process.kill(process.pid, signal);
  });
}

try {
  await run(containerCli, [
    'run',
    '--detach',
    '--name',
    containerName,
    '--publish',
    '127.0.0.1::5432',
    '--env',
    `POSTGRES_PASSWORD=${adminPassword}`,
    '--env',
    'POSTGRES_USER=postgres',
    '--env',
    'POSTGRES_DB=postgres',
    image,
  ]);
  containerStarted = true;
  await waitForPostgreSql();
  await psql(
    'postgres',
    `CREATE ROLE migrator LOGIN PASSWORD '${migratorPassword}';
CREATE ROLE runtime LOGIN PASSWORD '${runtimePassword}';
CREATE DATABASE skywright OWNER migrator;
`,
  );
  await psql(
    'skywright',
    `CREATE SCHEMA skywright AUTHORIZATION migrator;
GRANT USAGE ON SCHEMA skywright TO runtime;
`,
  );

  const publishedPort = await run(containerCli, [
    'port',
    containerName,
    '5432/tcp',
  ]);
  const port = /^127\.0\.0\.1:(\d+)$/u.exec(publishedPort)?.[1];
  if (!port) {
    throw new Error(
      `Could not determine PostgreSQL port from: ${publishedPort}`,
    );
  }
  const baseUrl = `jdbc:postgresql://127.0.0.1:${port}/skywright?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true`;
  const acceptanceEnvironment = {
    ...process.env,
    SKYWRIGHT_ACCEPTANCE_PORT: await availablePort(),
    SKYWRIGHT_DATABASE_RUNTIME_URL: `${baseUrl}&currentSchema=skywright`,
    SKYWRIGHT_DATABASE_RUNTIME_USERNAME: 'runtime',
    SKYWRIGHT_DATABASE_RUNTIME_PASSWORD: runtimePassword,
    SKYWRIGHT_DATABASE_MIGRATION_URL: baseUrl,
    SKYWRIGHT_DATABASE_MIGRATION_USERNAME: 'migrator',
    SKYWRIGHT_DATABASE_MIGRATION_PASSWORD: migratorPassword,
  };

  await run('pnpm', ['exec', 'playwright', 'test'], {
    env: acceptanceEnvironment,
    inherit: true,
  });
} catch (error) {
  if (containerStarted) {
    const logs = await run(containerCli, ['logs', containerName], {
      includeStderr: true,
    }).catch((logError) => `PostgreSQL logs unavailable: ${logError}`);
    console.error('PostgreSQL acceptance logs:\n', logs);
  }
  throw error;
} finally {
  await cleanup();
}
