"""Qualify the actual Kubernetes HTTP/websocket client and fixed node reader."""
import base64
from contextlib import closing
from datetime import datetime, timedelta, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import ipaddress
import json
import os
from pathlib import Path
import sqlite3
import ssl
import struct
import subprocess
import sys
import tempfile
import threading
from urllib.parse import parse_qs, urlsplit

from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

sys.path.insert(0, '/opt/skywright/runtime')
import log_collector as collector

NAME = 'skywright-38c76a5b-7cba-400e-9595-7657b194ea83'
CLUSTER = collector.cloud_name(NAME, 30) + '-91062'
CLOUD = collector.cloud_name(CLUSTER, 42, '12345678')
RAW = b'setup\r\n\x1b[31m\xff\x00task\rprogress\n'

with tempfile.TemporaryDirectory() as temporary:
    directory = Path(temporary)
    (directory / '.sky').mkdir()
    logs = directory / 'sky_logs/3-fixture'
    logs.mkdir(parents=True)
    (logs / 'run.log').write_bytes(RAW)
    database = directory / '.sky/jobs.db'
    with closing(sqlite3.connect(database)) as connection, connection:
        connection.execute('CREATE TABLE jobs(job_id INTEGER,job_name TEXT,run_timestamp TEXT,status TEXT,pid INTEGER,log_dir TEXT)')
        connection.execute('INSERT INTO jobs VALUES(3,?,?,?,?,?)', (NAME, 'timestamp-3', 'RUNNING', 0, str(logs)))
    key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    subject = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'localhost')])
    certificate = (x509.CertificateBuilder().subject_name(subject).issuer_name(subject).public_key(key.public_key())
        .serial_number(x509.random_serial_number()).not_valid_before(datetime.now(timezone.utc) - timedelta(minutes=1))
        .not_valid_after(datetime.now(timezone.utc) + timedelta(hours=1))
        .add_extension(x509.SubjectAlternativeName([x509.IPAddress(ipaddress.ip_address('127.0.0.1'))]), critical=False)
        .sign(key, hashes.SHA256()))
    cert = directory / 'certificate.pem'
    cert.write_bytes(certificate.public_bytes(serialization.Encoding.PEM))
    private = directory / 'key.pem'
    private.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    mode = {'replace': False, 'annotation': CLUSTER}
    commands = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def reply(self, value):
            body = json.dumps(value).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(body)))
            self.end_headers()
            self.wfile.write(body)

        def do_GET(self):
            assert self.headers.get('Authorization') == 'Bearer fixture-token'
            parsed = urlsplit(self.path)
            query = parse_qs(parsed.query)
            metadata = {'name': 'fixture-head', 'uid': 'pod-uid',
                        'labels': {'ray-node-type': 'head', 'ray-cluster-name': CLOUD},
                        'annotations': {'skypilot-cluster-name': mode['annotation']}}
            pod = {'metadata': metadata, 'spec': {'containers': [{'name': 'ray-node'}]}}
            if parsed.path == '/api/v1/namespaces/training/pods':
                assert query['labelSelector'] == [f'ray-cluster-name={CLOUD},ray-node-type=head']
                assert query['limit'] == ['2']
                self.reply({'items': [pod], 'metadata': {}})
            elif parsed.path.endswith('/exec'):
                command = query['command']
                assert command[:3] == ['python3', '-I', '-c']
                assert command[3] == collector.NODE_PROGRAM
                assert query['container'] == ['ray-node']
                assert query['tty'] == ['False'] or query['tty'] == ['false']
                commands.append(command)
                environment = dict(os.environ, HOME=str(directory), SKY_RUNTIME_DIR=str(directory))
                process = subprocess.run(command, env=environment, capture_output=True, timeout=3, check=True)
                assert not process.stderr
                self.send_response(101)
                self.send_header('Upgrade', 'websocket')
                self.send_header('Connection', 'Upgrade')
                accept = base64.b64encode(hashlib.sha1((self.headers['Sec-WebSocket-Key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').encode()).digest()).decode()
                self.send_header('Sec-WebSocket-Accept', accept)
                self.send_header('Sec-WebSocket-Protocol', 'v4.channel.k8s.io')
                self.end_headers()
                def frame(payload):
                    length = len(payload)
                    header = bytes([0x82, length]) if length < 126 else bytes([0x82, 126]) + struct.pack('!H', length)
                    self.wfile.write(header + payload)
                    self.wfile.flush()
                frame(b'\x01' + process.stdout[:17])
                frame(b'\x01' + process.stdout[17:])
                frame(b'\x03' + b'{"status":"Success"}')
                self.wfile.write(b'\x88\x00')
                self.wfile.flush()
                self.close_connection = True
            elif parsed.path == '/api/v1/namespaces/training/pods/fixture-head':
                if mode['replace']: metadata['uid'] = 'replacement'
                self.reply(pod)
            else:
                raise AssertionError(parsed.path)

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    tls = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls.load_cert_chain(cert, private)
    server.socket = tls.wrap_socket(server.socket, server_side=True)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    config = directory / 'kubeconfig'
    config.write_text(json.dumps({'apiVersion': 'v1', 'kind': 'Config',
        'users': [{'name': 'role', 'user': {'token': 'fixture-token'}}],
        'clusters': [{'name': 'cluster', 'cluster': {'server': f'https://127.0.0.1:{server.server_port}',
                     'certificate-authority-data': base64.b64encode(cert.read_bytes()).decode()}}],
        'contexts': [{'name': 'local', 'context': {'user': 'role', 'cluster': 'cluster', 'namespace': 'training'}}]}))
    config.chmod(0o400)
    os.environ['SKYWRIGHT_KUBECONFIG'] = str(config)
    class Sources(collector.Sources):
        def head(self, job, job_id): return CLUSTER, CLOUD, 'local'
    sources = Sources()
    job = {'taskName': NAME, 'recoveries': 0}
    try:
        cursor, captured = {}, b''
        for i in range(10):
            page = sources.remote(job, 91062, cursor, 7)
            captured += base64.b64decode(page['bytes'])
            cursor = page['cursor']
            assert not page['sealed']
            if page['endOfFile']: break
        assert captured == RAW
        with closing(sqlite3.connect(database)) as connection, connection:
            connection.execute("UPDATE jobs SET status='FAILED_SETUP',pid=2147483647")
        page = sources.remote(job, 91062, cursor, 7)
        assert page['sealed'] and page['lastGeneration']
        mode['replace'] = True
        try:
            sources.remote(job, 91062, {}, 7)
            raise AssertionError('replacement accepted')
        except collector.Unavailable as error:
            assert str(error) == 'SOURCE_REPLACED'
        mode['replace'] = False
        mode['annotation'] = 'unrelated'
        try:
            sources.remote(job, 91062, {}, 7)
            raise AssertionError('unrelated pod accepted')
        except collector.Unavailable as error:
            assert str(error) == 'SOURCE_HEAD_UNCONFIRMED'
        assert 'sky' not in sys.modules
        print('Kubernetes raw-byte protocol qualified')
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)
