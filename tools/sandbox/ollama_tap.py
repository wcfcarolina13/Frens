"""Loopback tap between the sandbox and Ollama, for checks that need the soul prompt text (BELIEFS, CURRENT STATE, ##FRENS contract).

  python3 tools/sandbox/ollama_tap.py [listen_port=11435] [upstream=http://127.0.0.1:11434] [out=~/Library/Caches/frens-ft/ollama_tap.jsonl]

Point the sandbox at it with the game closed: settings.json5 "ollamaBaseUrl": "http://127.0.0.1:11435", and back to 11434 after.
Each /api/chat request and response is appended to the JSONL file (sandbox prompts only, never Nirn's). Binds 127.0.0.1 only.
"""
import http.server, json, os, sys, time, urllib.request

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 11435
UP = sys.argv[2] if len(sys.argv) > 2 else "http://127.0.0.1:11434"
OUT = sys.argv[3] if len(sys.argv) > 3 else os.path.expanduser("~/Library/Caches/frens-ft/ollama_tap.jsonl")
os.makedirs(os.path.dirname(OUT), exist_ok=True)


class Tap(http.server.BaseHTTPRequestHandler):
    def _relay(self, body=None):
        req = urllib.request.Request(UP + self.path, data=body, method=self.command,
                                     headers={k: v for k, v in self.headers.items() if k.lower() not in ("host", "content-length")})
        try:
            with urllib.request.urlopen(req, timeout=300) as r:
                data, code, ctype = r.read(), r.status, r.headers.get("Content-Type", "application/json")
        except urllib.error.HTTPError as e:
            data, code, ctype = e.read(), e.code, "application/json"
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)
        return data, code

    def do_GET(self):
        self._relay()

    def do_POST(self):
        body = self.rfile.read(int(self.headers.get("Content-Length", 0)))
        t0 = time.time()
        data, code = self._relay(body)
        if self.path.startswith("/api/chat"):
            try:
                rec = {"t": time.strftime("%H:%M:%S"), "ms": int((time.time() - t0) * 1000), "code": code,
                       "request": json.loads(body), "response": json.loads(data)}
            except Exception:
                rec = {"t": time.strftime("%H:%M:%S"), "code": code, "raw": body.decode(errors="replace")[:20000]}
            with open(OUT, "a") as f:
                f.write(json.dumps(rec) + "\n")

    def log_message(self, *a):
        pass


http.server.ThreadingHTTPServer(("127.0.0.1", PORT), Tap).serve_forever()
