from pathlib import Path
from zipfile import ZipFile, ZipInfo, ZIP_DEFLATED, ZIP_STORED
import struct, hashlib, zlib, re, shutil, os

BASE = Path('/mnt/data/简阅_v742_日志直达闪退崩溃日志_单线缓存版.apk')
WORK = Path('/mnt/data/v743_work')
CLASSES3 = WORK/'classes3.dex'
MANIFEST = WORK/'AndroidManifest.xml'
G0 = WORK/'G0.xml'
UNSIGNED = WORK/'v743_unaligned_unsigned.apk'

# ---------- helpers ----------
def fix_dex_header(buf: bytearray):
    sig = hashlib.sha1(buf[32:]).digest()
    buf[12:32] = sig
    chk = zlib.adler32(buf[12:]) & 0xffffffff
    struct.pack_into('<I', buf, 8, chk)

def patch_const_string_jumbo(buf, off, reg, old_idx, new_idx):
    old = struct.pack('<HHH', 0x001b | (reg<<8), old_idx & 0xffff, (old_idx>>16)&0xffff)
    new = struct.pack('<HHH', 0x001b | (reg<<8), new_idx & 0xffff, (new_idx>>16)&0xffff)
    assert bytes(buf[off:off+6]) == old, (hex(off), bytes(buf[off:off+6]).hex(), old.hex())
    buf[off:off+6] = new

def patch_bg_region(buf, start, end, view_reg=1, tmp_reg=0, rid=0x7f08009c):
    # const v0, #rid  (31i)
    ins = struct.pack('<HHH', 0x0014 | (tmp_reg<<8), rid & 0xffff, (rid>>16)&0xffff)
    # invoke-virtual {v1,v0}, Landroid/view/View;.setBackgroundResource:(I)V method@020c
    # format 35c: A=2 args; C=v1, D=v0 -> third unit 0x0001
    ins += struct.pack('<HHH', 0x206e, 0x020c, (view_reg & 0xf) | ((tmp_reg & 0xf)<<4))
    region_len = end-start
    assert region_len % 2 == 0
    assert len(ins) <= region_len
    ins += b'\x00\x00' * ((region_len-len(ins))//2)
    buf[start:end] = ins

# ---------- patch classes3 ----------
b = bytearray(CLASSES3.read_bytes())
# Shelf selection: only the two selection-mode "取消" texts -> existing "×" string.
# old "取消" string index 0x10347; existing "×" index 0x102f0.
patch_const_string_jumbo(b, 0x1fac3a, 0, 0x10347, 0x102f0)
patch_const_string_jumbo(b, 0x1ff79a, 0, 0x10347, 0x102f0)

# ReaderActivity bookmark action: remove character text and use repurposed white vector resource as transparent background.
patch_const_string_jumbo(b, 0x206e18, 2, 0x103e5, 0x00000)  # "添" -> ""
assert bytes(b[0x206e4e:0x206e54]) == bytes.fromhex('22005b007010'), bytes(b[0x206e4e:0x206e54]).hex()
patch_bg_region(b, 0x206e4e, 0x206e84)

# ReadiumEpubActivity equivalent.
patch_const_string_jumbo(b, 0x214c0a, 2, 0x10400, 0x00000)  # "签" -> ""
assert bytes(b[0x214c40:0x214c46]) == bytes.fromhex('22005b007010'), bytes(b[0x214c40:0x214c46]).hex()
patch_bg_region(b, 0x214c40, 0x214c74)

fix_dex_header(b)
(WORK/'classes3_v743.dex').write_bytes(b)

# ---------- patch manifest version 742 -> 743 ----------
m = bytearray(MANIFEST.read_bytes())
old_code, new_code = 2098000742, 2098000743
old_bytes = struct.pack('<I', old_code)
assert m.count(old_bytes)==1, m.count(old_bytes)
m[m.find(old_bytes):m.find(old_bytes)+4] = struct.pack('<I',new_code)
old_name = '742'.encode('utf-16le')
new_name = '743'.encode('utf-16le')
assert m.count(old_name)==1, m.count(old_name)
pos=m.find(old_name); m[pos:pos+len(old_name)] = new_name
(WORK/'AndroidManifest_v743.xml').write_bytes(m)

# ---------- patch unused ExoPlayer vector res/G0.xml into white outline bookmark-plus ----------
x = bytearray(G0.read_bytes())
# Parse UTF-8 string pool (first chunk after XML header).
p = 8
type_, hs, size = struct.unpack_from('<HHI', x, p)
assert type_ == 0x0001 and hs >= 28
sc, stylec, flags, strings_start, styles_start = struct.unpack_from('<IIIII', x, p+8)
assert flags & 0x100
string_offsets = list(struct.unpack_from('<'+'I'*sc, x, p+hs))

def read_len(buf,pos):
    first=buf[pos]
    if first & 0x80:
        return (((first & 0x7f)<<8)|buf[pos+1], pos+2, 2)
    return (first,pos+1,1)

def enc_len_same_width(n,w):
    if w==1:
        assert n<0x80
        return bytes([n])
    assert n<0x8000
    return bytes([0x80 | ((n>>8)&0x7f), n & 0xff])

def get_entry(idx):
    base=p+strings_start+string_offsets[idx]
    ulen,pos,w1=read_len(x,base)
    blen,pos2,w2=read_len(x,pos)
    s=bytes(x[pos2:pos2+blen]).decode('utf-8')
    return base,pos2,ulen,blen,w1,w2,s

entries={get_entry(i)[-1]:i for i in range(sc)}
# Match paths by recognizable prefixes.
old_paths=[]
for i in range(sc):
    e=get_entry(i)
    s=e[-1]
    if s.startswith(('M','m')) and any(c in s for c in 'LlHhVvCcAaZz'):
        old_paths.append((i,s))

# Exact old paths in XML order by prefixes from stock exo_ic_audiotrack.
def idx_by_prefix(pref):
    found=[i for i,s in old_paths if s.startswith(pref)]
    assert len(found)==1,(pref,found)
    return found[0]
order=[
    idx_by_prefix('m171.46,133.99'),
    idx_by_prefix('m108.76,134.61'),
    idx_by_prefix('m83.65,156.16'),
    idx_by_prefix('m140.48,196.78'),
    idx_by_prefix('M83.37,156.17'),
    idx_by_prefix('m165.98,175.13'),
    idx_by_prefix('m239.41,144.34'),
]
new_paths=[
    'M80,45L232,45L232,270L156,220L80,270Z',   # bookmark outline
    'M156,100L156,170',                         # plus vertical
    'M120,135L192,135',                         # plus horizontal
    'M0,0','M0,0','M0,0','M0,0'
]

def replace_pool_string(idx,new_s):
    base,data_pos,old_ulen,old_blen,w1,w2,old_s=get_entry(idx)
    nb=new_s.encode('utf-8')
    ulen=len(new_s.encode('utf-16le'))//2
    assert len(nb) <= old_blen, (idx,len(nb),old_blen)
    x[base:base+w1] = enc_len_same_width(ulen,w1)
    len2pos=base+w1
    x[len2pos:len2pos+w2] = enc_len_same_width(len(nb),w2)
    start=data_pos
    # Clear old data plus terminator, then write new and terminator.
    x[start:start+old_blen+1] = b'\x00'*(old_blen+1)
    x[start:start+len(nb)] = nb
    x[start+len(nb)] = 0

for idx,new in zip(order,new_paths):
    replace_pool_string(idx,new)

# All seven path strokes: gray -> white; 3.0 -> 18.0 for visibility at 40dp.
gray = struct.pack('<I',0xffbcbbb9)
white = struct.pack('<I',0xffffffff)
assert x.count(gray)==7, x.count(gray)
x = bytearray(bytes(x).replace(gray,white))
f3 = struct.pack('<f',3.0); f18 = struct.pack('<f',18.0)
assert x.count(f3)==7, x.count(f3)
x = bytearray(bytes(x).replace(f3,f18))
(WORK/'G0_v743.xml').write_bytes(x)

# ---------- repack unsigned from v742, stripping signatures ----------
replacements={
    'classes3.dex': WORK/'classes3_v743.dex',
    'AndroidManifest.xml': WORK/'AndroidManifest_v743.xml',
    'res/G0.xml': WORK/'G0_v743.xml',
}
with ZipFile(BASE,'r') as zin, ZipFile(UNSIGNED,'w') as zout:
    for info in zin.infolist():
        name=info.filename
        if name.startswith('META-INF/'):
            continue
        data = replacements[name].read_bytes() if name in replacements else zin.read(name)
        ni=ZipInfo(filename=name,date_time=info.date_time)
        ni.compress_type=info.compress_type
        ni.comment=info.comment
        ni.extra=info.extra
        ni.internal_attr=info.internal_attr
        ni.external_attr=info.external_attr
        ni.create_system=info.create_system
        ni.flag_bits=info.flag_bits & ~0x08
        zout.writestr(ni,data)

print('built',UNSIGNED)
print('classes3 sha',hashlib.sha256((WORK/'classes3_v743.dex').read_bytes()).hexdigest())
print('vector sha',hashlib.sha256((WORK/'G0_v743.xml').read_bytes()).hexdigest())
