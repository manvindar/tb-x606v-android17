"""Reuse only checksum-verified compressed CI inputs; never cache expanded images."""
import argparse,hashlib,os,subprocess
from pathlib import Path
def verify(path,expected):
    with Path(path).open('rb') as f:actual=hashlib.file_digest(f,'sha256').hexdigest()
    if actual!=expected:raise ValueError('Input checksum mismatch: '+str(path))
def obtain(url,expected,path):
    if len(expected)!=64 or any(c not in '0123456789abcdef' for c in expected):raise ValueError('Invalid SHA256 pin')
    path=Path(path);path.parent.mkdir(parents=True,exist_ok=True)
    if path.exists():
        verify(path,expected);print('Verified cached input:',path);return path
    partial=path.with_name(path.name+'.partial')
    subprocess.run(['curl','-L','--fail','--retry','5','--retry-delay','10','-o',str(partial),url],check=True)
    verify(partial,expected);os.replace(partial,path);print('Verified downloaded input:',path);return path
if __name__=='__main__':
    a=argparse.ArgumentParser();a.add_argument('url');a.add_argument('sha256');a.add_argument('path');v=a.parse_args();obtain(v.url,v.sha256,v.path)
