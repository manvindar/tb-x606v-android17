import struct,subprocess,os,sys,hashlib
E='/opt/homebrew/opt/e2fsprogs/sbin/debugfs'
IMG=os.path.expanduser('~/Aistuff/android-aosp/work/a16/final/system-a16-tb-x606v.img')
def dump(path,dst):
    subprocess.run([E,'-R','dump %s %s'%(path,dst),IMG],capture_output=True)
    return os.path.exists(dst) and os.path.getsize(dst)>0
def needed(fn):
    d=open(fn,'rb').read()
    phoff,=struct.unpack_from('<Q',d,0x20); phsz,phn=struct.unpack_from('<HH',d,0x36)
    shoff,=struct.unpack_from('<Q',d,0x28); sz,cnt,si=struct.unpack_from('<HHH',d,0x3a)
    secs=[struct.unpack_from('<IIQQQQIIQQ',d,shoff+i*sz) for i in range(cnt)]
    out=[]
    for s in secs:
        if s[1]==6: # DYNAMIC
            strs=secs[s[6]]
            for k in range(s[5]//16):
                tag,val=struct.unpack_from('<qQ',d,s[4]+k*16)
                if tag==1:
                    e=d.index(b'\0',strs[4]+val); out.append(d[strs[4]+val:e].decode())
    return out
os.makedirs('lib64',exist_ok=True)
dump('/system/bin/audioserver','audioserver')
todo=needed('audioserver')+['libaudiohal@5.0.so','libaudiopolicymanagerdefault.so','libaudiopolicyenginedefault.so','libaudiopolicyengineconfigurable.so','libaudiohal.so','libaudiohal_deathhandler.so']; seen={}
while todo:
    n=todo.pop()
    if n in seen: continue
    dst='lib64/'+n
    ok=dump('/system/lib64/'+n,dst)
    seen[n]=ok
    if ok: todo+=needed(dst)
print(len(seen))
for n,ok in sorted(seen.items()): print(n,ok)
