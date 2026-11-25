#!/usr/bin/env python3
"""Public readiness check. Never needs credentials or reads shop records."""
import argparse
import json
import time
import urllib.request

parser = argparse.ArgumentParser()
parser.add_argument('--url', default='https://nigerian-retail-pos-cloud-a03da41beea8.herokuapp.com')
args = parser.parse_args()
if not args.url.startswith('https://'):
    parser.error('An HTTPS service address is required')
started = time.monotonic()
with urllib.request.urlopen(args.url.rstrip('/') + '/api/v1/health', timeout=20) as response:
    assert response.status == 200
    result = json.loads(response.read(4096))
assert result == {'status': 'ok', 'protocolVersion': 1}, 'Unexpected readiness response'
print(json.dumps({'healthy': True, 'protocolVersion': 1, 'responseMillis': round((time.monotonic()-started)*1000)}))
