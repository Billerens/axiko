"""Opens an Axiko backup (.axb) without the app: decrypts it into an ordinary ZIP and unpacks it.

    python scripts/backup/decrypt.py axiko-backup-2026-11-16.axb            asks for the password, writes
                                                                            axiko-backup-2026-11-16.zip beside
    python scripts/backup/decrypt.py copy.axb --out folder                  also unpacks into the folder and
                                                                            checks every file against the list
    python scripts/backup/decrypt.py copy.axb --password-file secret.txt    the password from a file (first line)

Needs Python 3.8+ and the `cryptography` package (pip install cryptography).
The format is described in docs/backup.md. What comes out is the owner's private data in the
clear, the passwords and keys of the app's vault among it (secrets.json): keep it accordingly.
"""
import argparse
import getpass
import hashlib
import hmac
import json
import os
import struct
import sys
import unicodedata
import zipfile
import zlib

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

MAGIC = b'AXIKOBAK'
HEADER = 80
TAG = 16
ROOTS = ('files/axiko/', 'files/data/', 'files/state/', 'files/drafts/', 'files/history/')
SINGLE = ('prefs/axiko.json', 'secrets.json')


class Refused(Exception):
    pass


def read_header(stream):
    raw = stream.read(HEADER)
    if len(raw) < 10 or raw[:8] != MAGIC:
        raise Refused('This is not an Axiko backup.')
    version = struct.unpack('>H', raw[8:10])[0]
    if version > 1:
        raise Refused('The backup has format %d; this script knows format 1. Take a newer script.' % version)
    if version < 1 or len(raw) < HEADER or zlib.crc32(raw[:76]) != struct.unpack('>I', raw[76:80])[0]:
        raise Refused('The header of the backup is damaged.')
    kdf, cipher, iterations, frame = struct.unpack('>BBII', raw[10:20])
    if kdf != 1 or cipher != 1 or not 1 <= iterations <= 50_000_000 or not 16 <= frame <= 16 << 20:
        raise Refused('The header of the backup is damaged.')
    return {'raw': raw, 'iterations': iterations, 'frame': frame, 'salt': raw[20:36], 'prefix': raw[36:43], 'check': raw[44:76]}


def derive(password, header):
    """The password (NFC, UTF-8) through PBKDF2-HMAC-SHA256, then one HMAC for each purpose."""
    secret = unicodedata.normalize('NFC', password).encode('utf-8')
    master = hashlib.pbkdf2_hmac('sha256', secret, header['salt'], header['iterations'], 32)
    check = hmac.new(master, b'axiko-backup/v1/check', hashlib.sha256).digest()
    if not hmac.compare_digest(check, header['check']):
        raise Refused('Wrong password.')
    return hmac.new(master, b'axiko-backup/v1/encrypt', hashlib.sha256).digest()


def frames(stream, header, key):
    """Yields the content frame by frame; a frame is the last one when nothing follows it."""
    aead = AESGCM(key)
    full = header['frame'] + TAG
    counter = 0
    block = stream.read(full)
    while True:
        following = stream.read(full)
        last = not following
        if len(block) < TAG or (not last and len(block) != full):
            raise Refused('The backup is damaged or cut short.')
        nonce = header['prefix'] + struct.pack('>IB', counter, 1 if last else 0)
        try:
            yield aead.decrypt(nonce, block, header['raw'])
        except InvalidTag:
            raise Refused('The backup is damaged or cut short.')
        if last:
            return
        block = following
        counter += 1


def decrypt(source, target, password):
    with open(source, 'rb') as stream:
        header = read_header(stream)
        key = derive(password, header)
        partial = target + '.part'
        try:
            with open(partial, 'wb') as out:
                for plain in frames(stream, header, key):
                    out.write(plain)
            os.replace(partial, target)
        finally:
            if os.path.exists(partial):
                os.remove(partial)


def allowed(path, directory=False):
    parts = path.split('/')
    if not path or path.startswith('/') or '\\' in path or any(part in ('', '.', '..') for part in parts):
        return False
    if any(ord(char) < 32 or ord(char) == 127 for char in path):
        return False
    if not directory and path in SINGLE:
        return True
    return any(path.startswith(root) or (directory and path == root[:-1]) for root in ROOTS)


def unpack(archive, folder):
    """Unpacks the ZIP, checking every file against manifest.json: size, SHA-256, nothing extra, nothing missing."""
    with zipfile.ZipFile(archive) as bundle:
        names = bundle.namelist()
        if not names or names[0] != 'manifest.json':
            raise Refused('The backup has no manifest.')
        raw = bundle.read('manifest.json')
        manifest = json.loads(raw.decode('utf-8'))
        if manifest.get('format', 0) > 1:
            raise Refused('The contents have format %s; this script knows format 1.' % manifest.get('format'))
        listed = {entry['path']: entry for entry in manifest['files']}
        dirs = set(manifest.get('dirs', []))
        if not all(allowed(path) for path in listed) or not all(allowed(path, True) for path in dirs):
            raise Refused('The manifest names a path outside the expected folders.')
        os.makedirs(folder, exist_ok=True)
        if os.listdir(folder):
            raise Refused('The folder to unpack into must be empty: ' + folder)
        base = os.path.realpath(folder)
        for path in sorted(dirs):
            os.makedirs(os.path.join(base, *path.split('/')), exist_ok=True)
        seen = set()
        for name in names[1:]:
            if name.endswith('/'):
                if name[:-1] not in dirs:
                    raise Refused('A folder that the manifest does not list: ' + name)
                continue
            entry = listed.get(name)
            if entry is None or name in seen:
                raise Refused('A file that the manifest does not list: ' + name)
            seen.add(name)
            target = os.path.join(base, *name.split('/'))
            os.makedirs(os.path.dirname(target), exist_ok=True)
            digest = hashlib.sha256()
            size = 0
            with bundle.open(name) as source, open(target, 'wb') as out:
                while True:
                    chunk = source.read(1 << 16)
                    if not chunk:
                        break
                    digest.update(chunk)
                    out.write(chunk)
                    size += len(chunk)
            if size != entry['size'] or digest.hexdigest() != entry['sha256']:
                raise Refused('A file differs from the manifest: ' + name)
            if entry.get('mtime'):
                os.utime(target, (entry['mtime'] / 1000, entry['mtime'] / 1000))
        if len(seen) != len(listed):
            raise Refused('Files listed in the manifest are missing.')
        with open(os.path.join(base, 'manifest.json'), 'wb') as out:
            out.write(raw)
        return manifest


def main():
    parser = argparse.ArgumentParser(description='Decrypts an Axiko backup (.axb) into a ZIP and unpacks it.')
    parser.add_argument('backup')
    parser.add_argument('--zip', help='where to write the decrypted ZIP (default: beside the backup)')
    parser.add_argument('--out', help='a new or empty folder to unpack into, with every file checked')
    parser.add_argument('--password-file', help='read the password from the first line of this file')
    args = parser.parse_args()

    if args.password_file:
        with open(args.password_file, encoding='utf-8') as source:
            password = source.readline().rstrip('\r\n')
    else:
        password = getpass.getpass('Password of the backup: ')
    archive = args.zip or os.path.splitext(args.backup)[0] + '.zip'
    try:
        decrypt(args.backup, archive, password)
        print('Decrypted: ' + archive)
        if args.out:
            manifest = unpack(archive, args.out)
            app = manifest.get('app', {})
            print('Unpacked and checked: %d files into %s' % (len(manifest['files']), args.out))
            print('Made by %s %s' % (app.get('id', '?'), app.get('version', '?')))
    except Refused as error:
        print(str(error), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
