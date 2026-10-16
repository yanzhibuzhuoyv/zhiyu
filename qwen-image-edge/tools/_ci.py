from math import comb
def Pge(p,x,n): return sum(comb(n,k)*p**k*(1-p)**(n-k) for k in range(x,n+1))
def Ple(p,x,n): return sum(comb(n,k)*p**k*(1-p)**(n-k) for k in range(0,x+1))
def inc(f,t):           # f 单调递增, 求 f(p)=t
    lo,hi=0.0,1.0
    for _ in range(200):
        m=(lo+hi)/2
        if f(m)<t: lo=m
        else: hi=m
    return (lo+hi)/2
def dec(f,t):           # f 单调递减, 求 f(p)=t
    lo,hi=0.0,1.0
    for _ in range(200):
        m=(lo+hi)/2
        if f(m)>t: lo=m
        else: hi=m
    return (lo+hi)/2
def cp(x,n,a=0.05):
    lo = 0.0 if x==0 else inc(lambda p: Pge(p,x,n), a/2)
    hi = 1.0 if x==n else dec(lambda p: Ple(p,x,n), a/2)
    return lo,hi
for tag,x,n in [("L36 合计",12,12),("L28 合计",6,12),("L24 合计",12,12),
                ("L36 E1",6,6),("L28 E1",0,6),("L24 E1",6,6),
                ("L36 E2",6,6),("L28 E2",6,6),("L24 E2",6,6)]:
    lo,hi=cp(x,n)
    print(f"{tag:<9} {x}/{n} = {x/n*100:5.1f}%   95%CI [{lo*100:5.1f}%, {hi*100:5.1f}%]")
