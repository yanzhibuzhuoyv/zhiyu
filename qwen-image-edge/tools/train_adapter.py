#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
训练对齐适配器：把学生多层隐状态映射到教师的 4096 维条件空间
  loss = (1 - cosine) + λ * MSE     只在有效 token 上算
  train/val 按 shard 文件切分（末尾若干 shard 作验证）
"""
import os, sys, json, glob, time, argparse
import torch
import torch.nn as nn


class Adapter(nn.Module):
    def __init__(self, in_dim, out_dim=4096, hidden=4096):
        super().__init__()
        self.norm = nn.LayerNorm(in_dim)
        self.net = nn.Sequential(nn.Linear(in_dim, hidden), nn.GELU(), nn.Linear(hidden, out_dim))

    def forward(self, x):
        return self.net(self.norm(x))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--teacher", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\distill\teacher")
    ap.add_argument("--student", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\distill\student")
    ap.add_argument("--out", default=r"C:\Users\32202\Desktop\99999\qwen-image-edge\distill\adapter")
    ap.add_argument("--epochs", type=int, default=60)
    ap.add_argument("--bs", type=int, default=64)
    ap.add_argument("--lr", type=float, default=3e-4)
    ap.add_argument("--lambda-mse", type=float, default=0.5)
    ap.add_argument("--val-shards", type=int, default=4)
    ap.add_argument("--eval-every", type=int, default=5)
    args = ap.parse_args()
    os.makedirs(args.out, exist_ok=True)
    dev = "cuda" if torch.cuda.is_available() else "cpu"

    tsh = sorted(glob.glob(os.path.join(args.teacher, "*.pt")))
    ssh = sorted(glob.glob(os.path.join(args.student, "*.pt")))
    assert len(tsh) == len(ssh) and tsh, f"shard 数量不匹配: {len(tsh)} vs {len(ssh)}"
    nv = min(args.val_shards, max(1, len(tsh) // 10))
    val_pairs = list(zip(tsh[-nv:], ssh[-nv:]))
    tr_pairs = list(zip(tsh[:-nv], ssh[:-nv]))
    print(f"共 {len(tsh)} shard -> train {len(tr_pairs)} / val {len(val_pairs)}", flush=True)

    s0 = torch.load(ssh[0], weights_only=False)
    in_dim = s0["feats"].shape[-1]
    print(f"学生特征维度 {in_dim} -> 教师 4096", flush=True)
    model = Adapter(in_dim).to(dev)
    print(f"适配器参数 {sum(p.numel() for p in model.parameters())/1e6:.1f} M", flush=True)
    opt = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    sched = torch.optim.lr_scheduler.CosineAnnealingLR(opt, T_max=args.epochs)

    def eval_cos(pairs, limit=None):
        model.eval(); sc, sn = 0.0, 0
        with torch.no_grad():
            for ft, fs in (pairs[:limit] if limit else pairs):
                T = torch.load(ft, weights_only=False); S = torch.load(fs, weights_only=False)
                x = S["feats"].float().to(dev)
                y = T["embeds"].float().to(dev)
                m = S["mask"].to(dev).bool() & T["mask"].to(dev).bool()
                p = model(x)
                sc += torch.nn.functional.cosine_similarity(p[m], y[m], dim=-1).sum().item()
                sn += int(m.sum())
        model.train()
        return sc / max(sn, 1)

    base = eval_cos(val_pairs)
    print(f"未训练基线（随机适配器）验证余弦 {base:.4f}", flush=True)

    best = -1.0
    for ep in range(1, args.epochs + 1):
        t0 = time.time(); tot, cnt = 0.0, 0
        g = torch.Generator().manual_seed(ep)
        order = torch.randperm(len(tr_pairs), generator=g).tolist()
        for pi in order:
            T = torch.load(tr_pairs[pi][0], weights_only=False)
            S = torch.load(tr_pairs[pi][1], weights_only=False)
            x = S["feats"].float(); sm = S["mask"].float()
            y = T["embeds"].float(); tm = T["mask"].float()
            n = x.shape[0]
            bsel = torch.randperm(n, generator=g).tolist()
            for j in range(0, n, args.bs):
                sel = bsel[j:j + args.bs]
                xb = x[sel].to(dev); yb = y[sel].to(dev)
                m = (sm[sel].to(dev).bool() & tm[sel].to(dev).bool())
                if m.sum() == 0:
                    continue
                p = model(xb)
                a, b = p[m], yb[m]
                cos = torch.nn.functional.cosine_similarity(a, b, dim=-1)
                loss = (1 - cos).mean() + args.lambda_mse * torch.nn.functional.mse_loss(a, b)
                opt.zero_grad(); loss.backward()
                torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                opt.step()
                tot += loss.item() * int(m.sum()); cnt += int(m.sum())
        sched.step()
        msg = f"  epoch {ep:3d}  loss {tot/max(cnt,1):.4f}"
        if ep % args.eval_every == 0 or ep == args.epochs:
            vc = eval_cos(val_pairs)
            msg += f"  val_cos {vc:.4f}"
            if vc > best:
                best = vc
                torch.save({"state_dict": model.state_dict(), "in_dim": in_dim,
                            "val_cos": vc, "epoch": ep}, os.path.join(args.out, "adapter_best.pt"))
        msg += f"  {time.time()-t0:.1f}s"
        print(msg, flush=True)

    torch.save({"state_dict": model.state_dict(), "in_dim": in_dim, "val_cos": best},
               os.path.join(args.out, "adapter_last.pt"))
    print(f"\n最佳验证余弦 {best:.4f} -> {args.out}\\adapter_best.pt", flush=True)


if __name__ == "__main__":
    sys.exit(main())
