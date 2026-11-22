#!/usr/bin/env python3
"""Aura exec server. Runs inside a GitHub Codespace on a private forwarded port (GitHub authenticates
every request), so it needs no secret of its own. VERSION is compared by the app to decide on updates."""
VERSION = "1"

import base64, json, os, subprocess, sys, threading, time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs

PORT = int(os.environ.get("AURA_PORT", "8787"))
HOME = os.path.expanduser("~")
OUT = os.path.join(HOME, "aura-out")
os.makedirs(OUT, exist_ok=True)
MCP_CMD = os.environ.get("AURA_MCP_CMD", "npx -y @playwright/mcp@latest --headless --no-sandbox")
MAX_OUT = 20000


def tail(s):
    return s if len(s) <= MAX_OUT else "...[truncated]...\n" + s[-MAX_OUT:]


class Mcp:
    """Keeps one MCP server (Playwright MCP by default) alive and speaks JSON-RPC over stdio."""

    def __init__(self):
        self.proc = None
        self.lock = threading.Lock()
        self.waiters = {}
        self.next_id = 1
        self.shots = 0

    def _reader(self):
        for line in self.proc.stdout:
            try:
                msg = json.loads(line)
            except Exception:
                continue
            ev = self.waiters.get(msg.get("id"))
            if ev is not None:
                ev["msg"] = msg
                ev["event"].set()

    def _rpc(self, method, params=None, timeout=120, notify=False):
        msg = {"jsonrpc": "2.0", "method": method}
        if params is not None:
            msg["params"] = params
        if notify:
            self.proc.stdin.write(json.dumps(msg) + "\n")
            self.proc.stdin.flush()
            return None
        rid = self.next_id
        self.next_id += 1
        msg["id"] = rid
        w = {"event": threading.Event(), "msg": None}
        self.waiters[rid] = w
        self.proc.stdin.write(json.dumps(msg) + "\n")
        self.proc.stdin.flush()
        if not w["event"].wait(timeout):
            self.waiters.pop(rid, None)
            raise TimeoutError("MCP call timed out: " + method)
        self.waiters.pop(rid, None)
        if "error" in w["msg"]:
            raise RuntimeError(json.dumps(w["msg"]["error"]))
        return w["msg"].get("result")

    def start(self):
        if self.proc and self.proc.poll() is None:
            return
        self.proc = subprocess.Popen(MCP_CMD, shell=True, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=open("/tmp/aura-mcp.log", "a"), text=True, bufsize=1)
        threading.Thread(target=self._reader, daemon=True).start()
        self._rpc("initialize", {"protocolVersion": "2024-11-05", "capabilities": {},
                                 "clientInfo": {"name": "aura", "version": VERSION}}, timeout=300)
        self._rpc("notifications/initialized", {}, notify=True)

    def tools(self):
        with self.lock:
            self.start()
            res = self._rpc("tools/list", {})
        return [{"name": t["name"], "description": (t.get("description") or "")[:200],
                 "inputSchema": t.get("inputSchema")} for t in res.get("tools", [])]

    def call(self, name, arguments):
        with self.lock:
            self.start()
            res = self._rpc("tools/call", {"name": name, "arguments": arguments or {}}, timeout=180)
        parts = []
        for c in res.get("content", []):
            if c.get("type") == "text":
                parts.append(c.get("text", ""))
            elif c.get("type") == "image":
                self.shots += 1
                path = os.path.join(OUT, "mcp-shot-%d.png" % self.shots)
                with open(path, "wb") as f:
                    f.write(base64.b64decode(c.get("data", "")))
                parts.append("[image saved at %s - fetch it with cloud_download]" % path)
        if res.get("isError"):
            parts.insert(0, "TOOL ERROR:")
        return tail("\n".join(parts))


MCP = Mcp()


class H(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *a):
        pass

    def _send(self, code, body, ctype="application/json"):
        if isinstance(body, (dict, list)):
            body = json.dumps(body).encode()
        elif isinstance(body, str):
            body = body.encode()
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _body(self):
        n = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(n) if n else b""

    def _path(self, q):
        p = (q.get("path") or [""])[0]
        return os.path.abspath(os.path.expanduser(p))

    def do_GET(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        try:
            if u.path == "/health":
                return self._send(200, {"ok": True, "version": VERSION})
            if u.path == "/file":
                p = self._path(q)
                if not os.path.isfile(p):
                    return self._send(404, {"error": "no such file: " + p})
                with open(p, "rb") as f:
                    return self._send(200, f.read(), "application/octet-stream")
            if u.path == "/mcp/tools":
                return self._send(200, MCP.tools())
            self._send(404, {"error": "not found"})
        except Exception as e:
            self._send(500, {"error": str(e)})

    def do_PUT(self):
        u = urlparse(self.path)
        q = parse_qs(u.query)
        try:
            if u.path == "/file":
                p = self._path(q)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "wb") as f:
                    f.write(self._body())
                return self._send(200, {"ok": True, "path": p})
            self._send(404, {"error": "not found"})
        except Exception as e:
            self._send(500, {"error": str(e)})

    def do_POST(self):
        u = urlparse(self.path)
        try:
            data = json.loads(self._body() or b"{}")
            if u.path == "/exec":
                cwd = data.get("cwd") or os.getcwd()
                try:
                    r = subprocess.run(data["cmd"], shell=True, executable="/bin/bash", cwd=cwd, capture_output=True,
                                       text=True, timeout=int(data.get("timeout") or 300))
                    return self._send(200, {"exit": r.returncode, "stdout": tail(r.stdout), "stderr": tail(r.stderr)})
                except subprocess.TimeoutExpired as e:
                    out = (e.stdout or b"").decode() if isinstance(e.stdout, bytes) else (e.stdout or "")
                    return self._send(200, {"exit": 124, "stdout": tail(out), "stderr": "timed out"})
            if u.path == "/mcp/call":
                return self._send(200, {"result": MCP.call(data["name"], data.get("arguments"))})
            self._send(404, {"error": "not found"})
        except Exception as e:
            self._send(500, {"error": str(e)})


if __name__ == "__main__":
    ThreadingHTTPServer(("0.0.0.0", PORT), H).serve_forever()
