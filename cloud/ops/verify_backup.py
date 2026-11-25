#!/usr/bin/env python3
"""Restore a Heroku backup into an isolated, temporary Postgres container, never the live DB.
Dump and SQL diagnostics remain private and are removed on exit. Prints only reviewed evidence.
"""
import argparse
import datetime
import json
import os
import pathlib
import secrets
import subprocess
import tempfile
import time
import uuid

parser = argparse.ArgumentParser()
parser.add_argument('--app', default='nigerian-retail-pos-cloud')
parser.add_argument('--image', default='postgres:17-alpine')
parser.add_argument('--capture', action='store_true', help='Capture a fresh backup before the restore drill')
parser.add_argument('--output', required=True, help='Destination for metadata-only verification evidence')
args = parser.parse_args()
os.umask(0o077)
name = 'retailpos-restore-' + uuid.uuid4().hex[:12]

def run(command, **kwargs):
    return subprocess.run(command, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=300, **kwargs)

try:
    with tempfile.TemporaryDirectory(prefix='retailpos-private-backup-') as directory:
        dump = pathlib.Path(directory) / 'backup.dump'
        if args.capture:
            run(['heroku', 'pg:backups:capture', '--app', args.app])
        run(['heroku', 'pg:backups:download', '--app', args.app, '--output', str(dump)])
        dump.chmod(0o600)
        assert dump.stat().st_size > 0
        run(['docker', 'run', '--detach', '--name', name, '--network', 'none', '--env', 'POSTGRES_PASSWORD='+secrets.token_urlsafe(32), '--env', 'POSTGRES_DB=retailpos_restore', args.image])
        for attempt in range(60):
            ready=subprocess.run(['docker','exec',name,'pg_isready','-U','postgres','-d','retailpos_restore'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
            if ready.returncode == 0:
                break
            time.sleep(1)
        else:
            raise RuntimeError('Temporary database did not become ready')
        with dump.open('rb') as source:
            run(['docker','exec','-i',name,'pg_restore','-U','postgres','--dbname','retailpos_restore','--no-owner','--no-acl','--exit-on-error'],stdin=source)
        sql="""SELECT CASE WHEN
            EXISTS(SELECT 1 FROM flyway_schema_history WHERE success)
            AND NOT EXISTS(SELECT 1 FROM pg_constraint WHERE contype IN ('f','c') AND NOT convalidated)
            AND NOT EXISTS(SELECT 1 FROM cloud_businesses WHERE jsonb_typeof(snapshot) <> 'object' OR revision<0)
            THEN 'verified' ELSE 'failed' END;"""
        check=run(['docker','exec',name,'psql','-U','postgres','-d','retailpos_restore','-tA','-c',sql]).stdout.decode().strip()
        assert check=='verified', 'Restore integrity checks failed'
        evidence={'verifiedAtUtc':datetime.datetime.now(datetime.timezone.utc).isoformat(),'app':args.app,'freshBackupCaptured':args.capture,'restoreTarget':'isolated temporary PostgreSQL container with no network','restoreSucceeded':True,'constraintsValidated':True,'snapshotShapeValidated':True,'productionDatabaseModified':False,'merchantRecordContentsExportedToEvidence':False,'domainRecoveryAndReplacementDeviceDrillPerformed':False}
        pathlib.Path(args.output).write_text(json.dumps(evidence,indent=2)+'\n')
        print(json.dumps(evidence))
except Exception as error:
    # CLI output can include signed download URLs/database addresses; never echo it.
    print(json.dumps({'restoreSucceeded':False,'errorType':type(error).__name__,'productionDatabaseModified':False}))
    raise SystemExit(1)
finally:
    subprocess.run(['docker','rm','--force',name],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
