"""Copy a sandbox save and set allowCommands (game closed): worldcopy.py "<source save>" "<new save>" 0|1. A copied save is a new world to Frens."""
import gzip, shutil, sys, os
saves = "/Users/roti/Library/Application Support/PrismLauncher/instances/mcp-sandbox/minecraft/saves"
src, dst, val = sys.argv[1], sys.argv[2], int(sys.argv[3])
d = os.path.join(saves, dst)
if src != dst:
    if os.path.exists(d): shutil.rmtree(d)
    shutil.copytree(os.path.join(saves, src), d)
p = os.path.join(d, "level.dat")
raw = gzip.open(p).read()
key = b"\x01\x00\x0dallowCommands"
i = raw.find(key); assert i >= 0, "no allowCommands"
print("was", raw[i+len(key)])
raw = raw[:i+len(key)] + bytes([val]) + raw[i+len(key)+1:]
with gzip.open(p, "wb") as f: f.write(raw)
print("now", val, d)
