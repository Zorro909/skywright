import { randomUUID } from 'node:crypto';
import { spawn } from 'node:child_process';

const image = process.env['SKYWRIGHT_POSTGRESQL_IMAGE'];

if (!image) {
  throw new Error(
    'SKYWRIGHT_POSTGRESQL_IMAGE must contain the repository-pinned PostgreSQL image',
  );
}

const containerCli = process.env['SKYWRIGHT_CONTAINER_CLI'] ?? 'docker';
const postgresContainerName = `skywright-acceptance-postgres-${randomUUID()}`;
const seaweedContainerName = `skywright-acceptance-seaweed-${randomUUID()}`;
const seaweedImage =
  'docker.io/chrislusf/seaweedfs:4.42@sha256:f7cbc8bdbbf60a1aaba7d61784a3bdff3ec1e0657f6ad0b26d5b6ab2cd9d0dc6';
const adminPassword = randomUUID();
const migratorPassword = randomUUID();
const runtimePassword = randomUUID();
let postgresStarted = false;
let seaweedStarted = false;

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

async function waitForPostgreSql() {
  let lastFailure;
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      await run(containerCli, [
        'exec',
        postgresContainerName,
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
      postgresContainerName,
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
  if (seaweedStarted) {
    seaweedStarted = false;
    await run(containerCli, ['rm', '--force', seaweedContainerName]).catch(
      (error) => {
        console.error(`Could not remove ${seaweedContainerName}:`, error);
      },
    );
  }
  if (postgresStarted) {
    postgresStarted = false;
    await run(containerCli, ['rm', '--force', postgresContainerName]).catch(
      (error) => {
        console.error(`Could not remove ${postgresContainerName}:`, error);
      },
    );
  }
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
    postgresContainerName,
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
  postgresStarted = true;
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
    postgresContainerName,
    '5432/tcp',
  ]);
  const port = /^127\.0\.0\.1:(\d+)$/u.exec(publishedPort)?.[1];
  if (!port) {
    throw new Error(
      `Could not determine PostgreSQL port from: ${publishedPort}`,
    );
  }
  await run(containerCli, [
    'run',
    '--detach',
    '--name',
    seaweedContainerName,
    '--publish',
    '127.0.0.1::8333',
    seaweedImage,
    'mini',
    '-master.telemetry=false',
  ]);
  seaweedStarted = true;
  const publishedSeaweedPort = await run(containerCli, [
    'port',
    seaweedContainerName,
    '8333/tcp',
  ]);
  const seaweedPort = /^127\.0\.0\.1:(\d+)$/u.exec(publishedSeaweedPort)?.[1];
  if (!seaweedPort) {
    throw new Error(
      `Could not determine SeaweedFS port from: ${publishedSeaweedPort}`,
    );
  }
  const seaweedEndpoint = `http://127.0.0.1:${seaweedPort}`;
  let seaweedFailure;
  for (let attempt = 0; attempt < 100; attempt += 1) {
    try {
      const response = await fetch(`${seaweedEndpoint}/acceptance-outputs`, {
        method: 'PUT',
      });
      if (response.ok) {
        seaweedFailure = undefined;
        break;
      }
      seaweedFailure = new Error(`SeaweedFS returned HTTP ${response.status}`);
    } catch (error) {
      seaweedFailure = error;
    }
    await delay(200);
  }
  if (seaweedFailure) {
    throw new Error('SeaweedFS did not become ready within 20 seconds', {
      cause: seaweedFailure,
    });
  }
  const baseUrl = `jdbc:postgresql://127.0.0.1:${port}/skywright?connectTimeout=5&socketTimeout=5&tcpKeepAlive=true`;
  const acceptanceEnvironment = {
    ...process.env,
    SKYWRIGHT_DATABASE_RUNTIME_URL: `${baseUrl}&currentSchema=skywright`,
    SKYWRIGHT_DATABASE_RUNTIME_USERNAME: 'runtime',
    SKYWRIGHT_DATABASE_RUNTIME_PASSWORD: runtimePassword,
    SKYWRIGHT_DATABASE_MIGRATION_URL: baseUrl,
    SKYWRIGHT_DATABASE_MIGRATION_USERNAME: 'migrator',
    SKYWRIGHT_DATABASE_MIGRATION_PASSWORD: migratorPassword,
    SKYWRIGHT_ACCEPTANCE_S3_ENDPOINT: seaweedEndpoint,
    AWS_ACCESS_KEY_ID: 'test-access-key',
    AWS_SECRET_ACCESS_KEY: 'test-secret-key',
    AWS_REGION: 'us-east-1',
    AWS_EC2_METADATA_DISABLED: 'true',
  };

  await run('pnpm', ['exec', 'playwright', 'test'], {
    env: acceptanceEnvironment,
    inherit: true,
  });
} catch (error) {
  if (postgresStarted) {
    const logs = await run(containerCli, ['logs', postgresContainerName], {
      includeStderr: true,
    }).catch((logError) => `PostgreSQL logs unavailable: ${logError}`);
    console.error('PostgreSQL acceptance logs:\n', logs);
  }
  if (seaweedStarted) {
    const logs = await run(containerCli, ['logs', seaweedContainerName], {
      includeStderr: true,
    }).catch((logError) => `SeaweedFS logs unavailable: ${logError}`);
    console.error('SeaweedFS acceptance logs:\n', logs);
  }
  throw error;
} finally {
  await cleanup();
}
