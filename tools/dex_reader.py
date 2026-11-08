import zipfile,sys,struct,json,re

def uleb(d,o):
 n=0;sh=0
 for i in range(5):
  b=d[o];o+=1;n|=(b&127)<<sh;sh+=7
  if b<128:break
 return n,o

def signed_val(v,n):
 mask=1<<(8*n-1);return (v ^ mask)-mask
class Dex:
 def __init__(self,d):
  self.d=d;u32=self.u32
  self.strings=[]
  for j in range(u32(56)):
   off=u32(u32(60)+j*4);_,off=uleb(d,off);end=d.find(b'\x00',off);self.strings.append(d[off:end].decode('utf-8','replace'))
  self.types=[self.strings[u32(u32(68)+4*j)] for j in range(u32(64))]
  self.protos=[]
  for j in range(u32(72)):
   short,ret,poff=struct.unpack_from('<III',d,u32(76)+j*12)
   args=[] if poff==0 else [self.types[self.u16(poff+4+i*2)] for i in range(u32(poff))]
   self.protos.append((self.types[ret],args))
  self.methods=[]
  for j in range(u32(88)):
   cid,pid,nidx=struct.unpack_from('<HHI',d,u32(92)+j*8)
   self.methods.append({'owner':self.types[cid],'name':self.strings[nidx],'ret':self.protos[pid][0],'params':self.protos[pid][1]})
  self.fields=[]
  for j in range(u32(80)):
   cid,tid,nameidx=struct.unpack_from('<HHI',d,u32(84)+j*8)
   self.fields.append((self.types[cid],self.types[tid],self.strings[nameidx]))
  self.classes={}
  for j in range(u32(96)):
   cid,access,sup,interfaces,src,annot,cloff,stat=struct.unpack_from('<8I',d,u32(100)+32*j)
   cname=self.types[cid];rec={'cname':cname,'annotation_off':annot,'methods':[],'fields':[]}
   self.classes[cname]=rec
   if not cloff:continue
   pos=cloff;sizes=[]
   for i in range(4):
    a,pos=uleb(d,pos);sizes.append(a)
   for i in range(2):
    idx=0
    for k in range(sizes[i]):
     diff,pos=uleb(d,pos);idx+=diff;flags,pos=uleb(d,pos)
     rec['fields'].append((idx,flags,self.fields[idx]))
   for i in range(2,4):
    idx=0
    for k in range(sizes[i]):
     diff,pos=uleb(d,pos);idx+=diff;flags,pos=uleb(d,pos);code,pos=uleb(d,pos)
     rec['methods'].append((idx,flags,code,self.methods[idx]))
 def u16(self,o):return struct.unpack_from('<H',self.d,o)[0]
 def u32(self,o):return struct.unpack_from('<I',self.d,o)[0]
 def ev(self,pos):
  head=self.d[pos];pos+=1;t=head&31;size=(head>>5)+1
  if t in [0x00,0x02,0x03,0x04,0x06,0x10,0x11]:
   raw=int.from_bytes(self.d[pos:pos+size],'little');return raw,pos+size
  if t in (0x17,0x18,0x19,0x1a,0x1b,0x1c):
   index=int.from_bytes(self.d[pos:pos+size],'little');pos+=size
   if t==0x17:return self.strings[index],pos
   if t==0x18:return self.types[index],pos
   if t in (0x19,0x1b):return str(self.fields[index]),pos
   if t==0x1a:return str(self.methods[index]),pos
   return f'annotation[{index}]',pos
  if t==0x1d:
   val,pos=self.encoded_annotation(pos);return val,pos
  if t==0x1c:
   sz,pos=uleb(self.d,pos);a=[]
   for _ in range(sz):
    v,pos=self.ev(pos);a.append(v)
   return a,pos
  if t==0x1e:return None,pos
  if t==0x1f:return bool(head>>5),pos
  return 'unknown',pos
 def encoded_annotation(self,pos):
  idx,pos=uleb(self.d,pos);n,pos=uleb(self.d,pos);vals={}
  for j in range(n):
   ni,pos=uleb(self.d,pos);v,pos=self.ev(pos);vals[self.strings[ni]]=v
  return {'type':self.types[idx],'values':vals},pos
 def annotations(self,off):
  if not off:return []
  n=self.u32(off);a=[]
  for i in range(n):
   po=self.u32(off+4+i*4)
   try: val,_=self.encoded_annotation(po+1);a.append(val)
   except Exception as e: a.append({'parse_error':str(e),'off':po})
  return a
 def method_annotations(self,c):
  off=self.classes[c]['annotation_off']
  if not off:return {},{}
  ca,fs,ms,ps=struct.unpack_from('<4I',self.d,off)
  op=off+16+fs*8
  ma={}
  for i in range(ms):
   idx,ano=struct.unpack_from('<II',self.d,op+i*8)
   ma[idx]=self.annotations(ano)
  pa={};op+=ms*8
  for i in range(ps):
   idx,po=struct.unpack_from('<II',self.d,op+i*8)
   size=self.u32(po)
   pa[idx]=[self.annotations(self.u32(po+4+j*4)) for j in range(size)]
  return ma,pa
 def code_strings(self,co):
  if not co:return []
  insn_size=self.u32(co+12);b=self.d[co+16:co+16+2*insn_size];out=[]
  # Targeted recognition in 16bit units (may yield false positives when middle of other instruction)
  for i in range(0,len(b)-4,2):
   opcode=b[i]
   if opcode==0x1a:
    idx=int.from_bytes(b[i+2:i+4],'little')
    if idx<len(self.strings):
     out.append((i//2,'const-string',self.strings[idx]))
   if opcode==0x1b and i+6<=len(b):
    idx=int.from_bytes(b[i+2:i+6],'little')
    if idx<len(self.strings):out.append((i//2,'const-string/jumbo',self.strings[idx]))
   if opcode in (0x6e,0x6f,0x70,0x71,0x72,0x74,0x75,0x76,0x77,0x78):
    idx=int.from_bytes(b[i+2:i+4],'little')
    if idx<len(self.methods):
     m=self.methods[idx]
     if any(x in m['owner'].lower() for x in ['retrofit','mqtt','okhttp','bluetooth','javax/net/ssl','tailg']):
      out.append((i//2,'invoke',m['owner']+'->'+m['name']+'('+','.join(m['params'])+')'))
  # Dedup
  seen=set();filtered=[]
  for a in out:
   k=(a[1],a[2])
   if k not in seen:filtered.append(a);seen.add(k)
  return filtered

if __name__=='__main__':
 APK=sys.argv[1]
 DNAME=sys.argv[2] if len(sys.argv)>2 else 'classes2.dex'
 with zipfile.ZipFile(APK) as z:d=z.read(DNAME)
 x=Dex(d)
 targets=sys.argv[3:] or ['Lcom/tailg/run/intelligence/net/TailgService;']
 for cname in targets:
  if cname not in x.classes:print('NO CLASS',cname);continue
  rec=x.classes[cname];ma,pa=x.method_annotations(cname)
  print('\n###',cname,'fields',len(rec['fields']),'methods',len(rec['methods']))
  for f in rec['fields'][:120]:print(' FIELD',f[2])
  for idx,flags,code,m in rec['methods']:
   if cname.endswith('TailgService;') and m['name'] not in ['login','loginCode','sendVerCode','getCode','getSecret','userCarList','userCarListPage','userCarPage','getCarList','getCarInfo','getCarControlInfo','remoteStart','remoteStop','remoteLock','remoteUnLock','remoteSearch','remoteSelfCheck','deviceTravel','deviceTravelDetail','getCarList','latestRecord','syncCar','getUserProfile','getUserDetailInfo','vehicleBind']:
    continue
   if len(targets)>1 and len(rec['methods'])>25 and not any(t in m['name'].lower() for t in ['<init>','<clinit>','connect','lock','login','pair','init','send','scan','auto','get','control','start','stop','verify','set','refresh']):continue
   print('\n METHOD',m['name'],m['ret'],m['params'],'idx',idx)
   for a in ma.get(idx,[]): print('  ANNOT',a)
   if idx in pa:print('  PARAM ANNOT',pa[idx])
   for i,kind,s in x.code_strings(code)[:38]:print('  ',kind,repr(s)[:250])
