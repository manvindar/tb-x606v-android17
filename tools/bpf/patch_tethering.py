"""Measured latest tethering patch port; full input digest, exact windows and branch targets required.
Preserves the previous build's scoped compatibility repairs; no new patch categories.
"""
import hashlib,json,struct,sys
from pathlib import Path
SPEC=json.loads(Path(__file__).with_name('tethering-DP11.261030.003.json').read_text())
def digest(data):return hashlib.sha256(data).hexdigest()
def branch_target(word,offset):
    if word>>24 in (0x34,0x35,0x54): bits,width=(word>>5)&0x7ffff,19
    elif word>>24 in (0x36,0x37):bits,width=(word>>5)&0x3fff,14
    else:raise ValueError('Unexpected conditional branch')
    if bits&(1<<(width-1)):bits-=1<<width
    return offset+bits*4
def patch(tool,data):
    record=SPEC[tool]
    if len(data)!=record['bytes'] or digest(data)!=record['sha256']:raise ValueError('Unknown tethering input build')
    result=bytearray(data);changed=[]
    for site in record['patches']:
        off=site['offset'];word=struct.unpack_from('<I',data,off)[0]
        if word!=site['word'] or digest(data[off-32:off+36])!=site['window_sha256']:raise ValueError('Patch instruction/window mismatch')
        actual_target=branch_target(word,off)
        if not 0<=actual_target<len(data):raise ValueError('Branch target outside image')
        target=site['target']
        if target is not None:
            if actual_target!=target or word>>24 not in (0x34,0x35,0x36,0x37):raise ValueError('Existing skip target mismatch')
            replacement=0x14000000|(((target-off)>>2)&0x3ffffff)
        else:replacement=0xd503201f
        struct.pack_into('<I',result,off,replacement);changed.append({'offset':off,'before':hex(word),'after':hex(replacement),'original_target':actual_target})
    allowed={x['offset']+i for x in record['patches'] for i in range(4)}
    if any(i not in allowed for i,(a,b) in enumerate(zip(data,result)) if a!=b):raise ValueError('Unexpected byte change')
    return bytes(result),{'tool':tool,'build':'DP11.261030.003','input_sha256':digest(data),'output_sha256':digest(result),'patches':changed,'runtime_validation':False}
if __name__=='__main__':
    tool,src,dst=sys.argv[1:];result,receipt=patch(tool,Path(src).read_bytes());Path(dst).write_bytes(result);Path(dst+'.tethering-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print('Measured tethering patch:',tool,receipt['output_sha256'])
