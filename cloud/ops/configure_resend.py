#!/usr/bin/env python3
"""Configure Resend privately; the key never enters shell arguments, logs or git."""
import argparse
import getpass
import json
import re
import subprocess
import urllib.request
import urllib.parse

parser = argparse.ArgumentParser()
parser.add_argument('--app', default='nigerian-retail-pos-cloud')
parser.add_argument('--sender', required=True, help='An address on your verified Resend domain')
args = parser.parse_args()
if not re.fullmatch(r'[^\s<>@]+@[^\s<>@]+\.[^\s<>@]+', args.sender):
    parser.error('Use a sender address on your verified Resend domain')
key = getpass.getpass('Resend sending API key (hidden): ').strip()
if not key.startswith('re_') or any(c.isspace() for c in key):
    raise SystemExit('A Resend API key is required; nothing was changed.')
token = subprocess.run(['heroku','auth:token'],check=True,capture_output=True,text=True,timeout=20).stdout.strip()
config = {'CLOUD_MAIL_HOST':'smtp.resend.com','CLOUD_MAIL_PORT':'587','CLOUD_MAIL_USERNAME':'resend','CLOUD_MAIL_PASSWORD':key,'CLOUD_MAIL_FROM':args.sender}
request = urllib.request.Request('https://api.heroku.com/apps/'+urllib.parse.quote(args.app,safe='')+'/config-vars',data=json.dumps(config).encode(),method='PATCH',headers={'Authorization':'Bearer '+token,'Accept':'application/vnd.heroku+json; version=3','Content-Type':'application/json'})
try:
    with urllib.request.urlopen(request,timeout=30) as response:
        assert response.status == 200
    print('Resend settings saved. Verify email delivery in the app before relying on recovery.')
except Exception:
    raise SystemExit('Could not save email settings. Check Heroku access and retry; credentials were not printed.')
