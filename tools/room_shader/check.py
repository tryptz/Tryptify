"""Compiles the Atmos room's shader with Skia's runtime-effect compiler, the one
Android runs AGSL through, and draws one frame of it with the app's camera
maths: the front-left speaker alone, lit, and one object at the back right.

A shader the compiler refuses raises here with the compiler's message; on a
phone it would be caught and the room drawn on the canvas instead, with
nothing to say why. Run it after changing ROOM_SRC or RoomCamera:

    pip install skia-python numpy
    python3 tools/room_shader/check.py [yaw] [out.png]

In the picture the lit speaker must be at the far wall on the left, its wave
fronts spreading from it, and the object near the camera on the right.
"""
import os,re,skia,math,numpy as np,sys
HERE=os.path.dirname(os.path.abspath(__file__))
kt=open(os.path.join(HERE,'../../app/src/main/java/tf/monochrome/android/ui/settings/AtmosRoomShader.kt')).read()
src=re.search(r'private const val ROOM_SRC = """\n(.*?)"""',kt,re.S).group(1)
eff=skia.RuntimeEffect.MakeForShader(src)
# Skia packs a runtime effect's uniforms in the order they are declared, four bytes a float, no padding.
sizes={'float':1,'float2':2,'float3':3,'float4':4}
layout={}; off=0
for t,n,c in re.findall(r'uniform (float[234]?) (\w+)(?:\[(\d+)\])?;',src):
    k=sizes[t]*(int(c) if c else 1); layout[n]=(off,k); off+=4*k
buf=bytearray(off)
def put(name,vals):
    o,k=layout[name]; a=np.array(vals,dtype='f4').flatten(); assert len(a)==k,(name,len(a),k); buf[o:o+4*k]=a.tobytes()
W,H=720,626
RW,RD,RH=5.0,5.0,2.75; ear=1.2; ceil=(0.5,0.5,1.0); yaw=float(sys.argv[1]) if len(sys.argv)>1 else -0.5
PITCH=0.42; FOCAL=1.35
fwd=[math.sin(yaw)*math.cos(PITCH), -math.cos(yaw)*math.cos(PITCH), -math.sin(PITCH)]
rx,ry=-fwd[1],fwd[0]; rn=math.hypot(rx,ry); right=[rx/rn,ry/rn,0.0]
up=[fwd[1]*right[2]-fwd[2]*right[1], fwd[2]*right[0]-fwd[0]*right[2], fwd[0]*right[1]-fwd[1]*right[0]]
ball=0.5*math.sqrt(RW*RW+RD*RD+RH*RH); half=math.atan(0.5*min(1,W/H)/FOCAL); dist=0.92*ball/math.sin(half)
eye=[RW/2-fwd[0]*dist, RD/2-fwd[1]*dist, RH*0.42-fwd[2]*dist]
print('right',[round(v,2) for v in right],'up',[round(v,2) for v in up])
# The 15.1.12 room's speakers (x, y, z, LFE), as AtmosPipeline::room_layout gives them.
L=[(0.5,0,0,0),(0.25,0,0,0),(0.75,0,0,0),(0,0,0,0),(1,0,0,0),(0,0.25,0,0),(1,0.25,0,0),(0,0.5,0,0),(1,0.5,0,0),(0,0.75,0,0),(1,0.75,0,0),(0,1,0,0),(1,1,0,0),(0.25,1,0,0),(0.75,1,0,0),(0.5,0.5,0,1),
   (0,0,0.5,0),(1,0,0.5,0),(0,1/3,0.5,0),(1,1/3,0.5,0),(0,2/3,0.5,0),(1,2/3,0.5,0),(0,1,0.5,0),(1,1,0.5,0),(0.25,0.25,1,0),(0.75,0.25,1,0),(0.25,0.75,1,0),(0.75,0.75,1,0)]
def place(x,y,z):
    Z=ear+z*(RH-ear); r=min(1,max(0,(Z-ear)/(RH-ear))); lx=(1-ceil[0])/2*r*RW; ly=(1-ceil[1])/2*r*RD
    return (min(max(x*RW,lx),RW-lx), min(max(y*RD,ly),RD-ly), Z)
spk=np.zeros((32,4),'f4'); kind=np.zeros(32,'f4')
for i,(x,y,z,lfe) in enumerate(L):
    if lfe: spk[i]=(0.36*RW,0.0,0.25,0.5); kind[i]=2
    else: spk[i]=(*place(x,y,z),0.0); kind[i]=1 if z>0 else 0
fl=[i for i,(x,y,z,lfe) in enumerate(L) if x==0 and y==0 and z==0 and not lfe][0]; spk[fl][3]=1.0
src4=np.zeros((12,4),'f4'); src4[0]=(*spk[fl][:3],1.0); ray=np.zeros((3,4),'f4'); ray[0]=(*spk[fl][:3],1.0)
obj=np.zeros((32,4),'f4'); obj[0]=(RW*0.9,RD*0.9,ear,0.9)
for n,v in dict(uRes=(W,H),uTime=[0.3],uEye=eye,uRight=right,uUp=up,uFwd=fwd,uFocal=[FOCAL],uRoom=(RW,RD,RH),uEar=[ear],uLean=[ear],uCeil=ceil,uCorner=[0.0],uListener=(2.5,2.5,ear),
                uSpk=spk,uSpkKind=kind,uSpkCount=[28.0],uObj=obj,uObjCount=[1.0],uSrc=src4,uSrcCount=[1.0],uRay=ray,uMark=np.zeros(32),uMarkCount=[0.0],
                uWave=(1.3,1.0),uField=[0.42],uGlow=[0.3],uPrimary=(0.925,0.675,0.157),uTertiary=(0.47,0.78,0.90),uInk=(0.92,0.94,0.94),uGrid=[1.0]).items(): put(n,v)
shader=eff.makeShader(skia.Data.MakeWithCopy(bytes(buf)))
surf=skia.Surface(W,H); c=surf.getCanvas(); c.clear(skia.Color(8,40,50)); p=skia.Paint(); p.setShader(shader); c.drawRect(skia.Rect.MakeWH(W,H),p)
out=sys.argv[2] if len(sys.argv)>2 else 'room.png'
surf.makeImageSnapshot().save(out, skia.kPNG); print('rendered', out)
