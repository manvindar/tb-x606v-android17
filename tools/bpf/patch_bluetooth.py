"""Exact guarded port of the three established Bluetooth compatibility sites.
Preserve conditional handling; redirect only unexpected status paths to measured normal exits.
Static patch evidence is not controller or pairing runtime verification.
"""
import hashlib,json,struct,sys
from pathlib import Path
SPEC=json.loads(Path(__file__).with_name('bluetooth-DP11.261030.003.json').read_text())
def digest(data):return hashlib.sha256(data).hexdigest()
def tbz_target(word,off):
    value=(word>>5)&0x3fff
    if value&0x2000:value-=0x4000
    return off+value*4
def patch(data):
    if len(data)!=SPEC['bytes'] or digest(data)!=SPEC['sha256']:raise ValueError('Unknown Bluetooth input build')
    result=bytearray(data);changes=[]
    for site in SPEC['sites']:
        off=site['offset'];word=struct.unpack_from('<I',data,off)[0]
        if word!=site['word'] or digest(data[off-32:off+36])!=site['window_sha256']:raise ValueError('Bluetooth exact instruction/window mismatch')
        register=20 if site['normal_exit'] is not None else 0
        if word&0xfff8001f!=0x36000000|register or tbz_target(word,off)!=site['target']:raise ValueError('Bluetooth conditional/error target mismatch')
        if site['normal_exit'] is None:replacement=0xd503201f
        else:
            dest=site['normal_exit'];delta=dest-off
            if delta%4 or not -(1<<15)<=delta<(1<<15) or not 0<=dest<len(data):raise ValueError('Bluetooth normal exit out of range')
            replacement=(word&~(0x3fff<<5))|(((delta>>2)&0x3fff)<<5)
            if replacement&0xfff8001f!=word&0xfff8001f:raise ValueError('Bluetooth condition changed')
        struct.pack_into('<I',result,off,replacement);changes.append(dict(site,replacement=hex(replacement)))
    allowed={s['offset']+i for s in SPEC['sites'] for i in range(4)}
    if any(i not in allowed for i,(a,b) in enumerate(zip(data,result)) if a!=b):raise ValueError('Unexpected Bluetooth byte change')
    return bytes(result),{'input_sha256':digest(data),'output_sha256':digest(result),'build':'DP11.261030.003','changes':changes,'runtime_validation':False}
if __name__=='__main__':
    src,dst=sys.argv[1:];result,receipt=patch(Path(src).read_bytes());Path(dst).write_bytes(result);Path(dst+'.bluetooth-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('Measured Bluetooth patch:',receipt['output_sha256'])
