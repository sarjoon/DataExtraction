"""Execute a generated package with a fake SQLcl process; no database required."""
import csv
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import zipfile

BASE=Path(__file__).resolve().parents[4]
ARTIFACT=BASE/'outputs/extraction-verification/sample.zip'
BASH=Path('C:/Program Files/Git/bin/bash.exe')
CYGPATH='C:/Program Files/Git/usr/bin/cygpath.exe'

def unix(path):
    path=Path(path).resolve().as_posix()
    return '/'+path[0].lower()+path[2:] if len(path)>1 and path[1]==':' else path

STUB='''import csv,json,os,re,subprocess,sys
from pathlib import Path
text=sys.stdin.read()
match=re.search(r'@"([^"]+)" "([^"]+)"',text)
if not match:raise ValueError('Missing script invocation: '+repr(text))
sql,output=match.groups()
entity=Path(sql).stem
if os.environ.get('FAIL_ENTITY')==entity:sys.exit(9)
output=subprocess.check_output([os.environ['TEST_CYGPATH'],'-w',output],text=True).strip()
config=json.loads((Path(os.environ['TEST_ROOT'])/'extraction.json').read_text())
definition=next(e for e in config['entities'] if e['id']==entity)
with open(output,'w',encoding='utf-8',newline='') as stream:
 writer=csv.writer(stream);writer.writerow([c['alias'] for c in definition['columns']])
 writer.writerow(['V2' if c['numeric'] else 'VJOHN JAMES' for c in definition['columns']])
'''

@unittest.skipUnless(ARTIFACT.exists() and BASH.exists(),'Generated sample ZIP and Git Bash required')
class PackageTest(unittest.TestCase):
    def run_package(self, root, fail=False):
        with zipfile.ZipFile(ARTIFACT) as package:package.extractall(root)
        (root/'stub.py').write_text(STUB,encoding='utf-8')
        stub=root/'fake-sqlcl'
        stub.write_text('#!/usr/bin/env bash\nexec "$PYTHON" "$ROOT/stub.py"\n',encoding='utf-8')
        stub.chmod(0o755)
        environment=os.environ.copy()
        environment.update(SQLCL=unix(stub),PYTHON=unix(sys.executable),BUSINESS_DATE='20261022',EDAG_CONNECT='/@TEST',TEST_ROOT=str(root),TEST_CYGPATH=CYGPATH)
        if fail:environment['FAIL_ENTITY']='t11'
        result=subprocess.run([str(BASH),unix(root/'run.sh')],env=environment,capture_output=True,text=True,timeout=60)
        result.stderr+='\n'.join(p.read_text(encoding='utf-8') for p in root.glob('output/*/logs/*.log'))
        return result

    def test_full_package_with_spaces_and_successful_transfer(self):
        with tempfile.TemporaryDirectory(prefix='edag package ') as directory:
            root=Path(directory);result=self.run_package(root)
            self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            customers=list(root.glob('output/*/APP_CUSTOMERS'));accounts=list(root.glob('output/*/APP_ACCOUNTS'))
            self.assertEqual(len(customers),1);self.assertEqual(len(accounts),1)
            self.assertIn('D 2 "JOHN JAMES"',customers[0].read_text())
            self.assertTrue(accounts[0].read_text().endswith('T 1 2\n'))
            self.assertEqual(list(root.glob('output/**/*.part')),[])

    def test_worker_failure_propagates_without_publishing_failed_entity(self):
        with tempfile.TemporaryDirectory(prefix='edag failure ') as directory:
            root=Path(directory);result=self.run_package(root,True)
            self.assertNotEqual(result.returncode,0)
            self.assertEqual(len(list(root.glob('output/*/APP_CUSTOMERS'))),1)
            self.assertEqual(list(root.glob('output/*/APP_ACCOUNTS')),[])
            self.assertEqual(list(root.glob('output/**/*.part')),[])

if __name__=='__main__':unittest.main()
