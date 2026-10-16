import zlib, math, sys
def crc(s): return zlib.crc32(s.encode())
def run(T):
    M=5000; S=0.7
    H=sum(1/n**S for n in range(1,M+1))
    cum=0; hi_prev=0; size={}
    acc=0.0
    for k in range(1,M+1):
        acc+=1/k**S
        hi=round(T*acc/H)
        size[k]=hi-hi_prev; hi_prev=hi
    rows=[(crc(f"pid:{k}:{j}"),k,j) for k in range(1,M+1) for j in range(1,size[k]+1)]
    rows.sort()
    brand={i+1:r[1] for i,r in enumerate(rows)}
    ids=sorted(brand, key=lambda i:(crc(f"pop:{i}"),i))
    rank={pid:r+1 for r,pid in enumerate(ids)}
    deleted={i for i in brand if brand[i] in (300,700) or crc(f"del:{i}")%100<12}
    alive=T-len(deleted)
    C=(0.78*T)**0.85
    L={i:int(math.floor(C/(rank[i]**0.85))) for i in brand}
    tot=sum(L.values())
    top=sum(sorted(L.values(),reverse=True)[:T//100])
    zeros=sum(1 for v in L.values() if v==0)
    def price(i):
        u1=(crc(f"u1:{i}")+1)/4294967297; u2=crc(f"u2:{i}")/4294967296
        z=math.sqrt(-2*math.log(u1))*math.cos(2*math.pi*u2)
        return min(60000,max(3000,round(13000*math.exp(0.6*z),-2)))
    pr=sorted(price(i) for i in brand if i not in deleted)
    alive_b=lambda k: sum(1 for i in brand if brand[i]==k and i not in deleted)
    print(T, dict(alive=alive, deleted_pct=round(100*len(deleted)/T,2),
        b1=(size[1],alive_b(1)), b100=(size[100],alive_b(100)), b1000=(size[1000],alive_b(1000)),
        b300=size[300], b700=size[700],
        likes=tot, top1=round(top/tot,3), zero=round(zeros/T,3), maxL=max(L.values()),
        price_min=pr[0], price_max=pr[-1], price_med=pr[len(pr)//2], distinct_prices=len(set(pr))))
run(int(sys.argv[1]))
