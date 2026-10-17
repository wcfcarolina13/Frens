"""Flat 25x25 test pad on solid stone with one oak tree 3 blocks east of the centre: pad.py X Y Z [trunk height]. Needs commands on."""
import os, subprocess, sys
X, Y, Z = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]); H = int(sys.argv[4]) if len(sys.argv) > 4 else 8
def c(*t):
    subprocess.run(["python3", os.path.join(os.path.dirname(__file__), "ft.py"), "chat", *t], capture_output=True)
c(f"/fill {X-12} {Y} {Z-12} {X+12} {Y+24} {Z+12} air", f"/fill {X-12} {Y-1} {Z-12} {X+12} {Y-1} {Z+12} grass_block",
  f"/fill {X-12} {Y-3} {Z-12} {X+12} {Y-2} {Z+12} dirt", f"/fill {X-12} {Y-30} {Z-12} {X+12} {Y-4} {Z+12} stone",
  f"/fill {X+3} {Y} {Z} {X+3} {Y+H-1} {Z} oak_log",
  f"/fill {X+1} {Y+H-3} {Z-2} {X+5} {Y+H} {Z+2} oak_leaves[persistent=false] keep")
print("pad ok")
