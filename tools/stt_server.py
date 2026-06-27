#!/usr/bin/env python3
"""Local faster-whisper STT server — OpenAI-compatible API."""
import os, sys, tempfile, json, io
from http.server import HTTPServer, BaseHTTPRequestHandler
from urllib.parse import urlparse
import cgi

_model = None
_MODEL_SIZE = "small"  # much better Chinese than "base"

def get_model():
    global _model
    if _model is None:
        print(f"Loading faster-whisper model: {_MODEL_SIZE} ...", flush=True)
        from faster_whisper import WhisperModel
        _model = WhisperModel(_MODEL_SIZE, device="cpu", compute_type="int8")
        print("Model loaded!", flush=True)
    return _model

class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        path = urlparse(self.path).path
        if path not in ("/v1/audio/transcriptions", "/audio/transcriptions"):
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b'{"error":"not found"}')
            return
        
        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in content_type:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b'{"error":"expected multipart/form-data"}')
            return
        
        # Parse multipart
        form = cgi.FieldStorage(
            fp=self.rfile,
            headers=self.headers,
            environ={
                "REQUEST_METHOD": "POST",
                "CONTENT_TYPE": content_type,
            }
        )
        
        file_item = form.getfirst("audio") or form.getfirst("file")
        if file_item is None:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b'{"error":"audio is required"}')
            return
        
        audio_bytes = file_item if isinstance(file_item, bytes) else file_item.encode() if isinstance(file_item, str) else b""
        if not audio_bytes:
            self.send_response(400)
            self.end_headers()
            self.wfile.write(b'{"error":"empty audio"}')
            return
        
        lang = form.getfirst("language") or "zh"  # default to Chinese
        
        print(f"STT request: {len(audio_bytes)} bytes, lang={lang}", flush=True)
        
        try:
            m = get_model()
            # initial_prompt helps whisper understand context (simplified Chinese, math, etc.)
            segments, info = m.transcribe(
                io.BytesIO(audio_bytes),
                language=lang,
                initial_prompt="以下是普通话的句子。1+1等于几。",
                beam_size=5,
                vad_filter=True,
            )
            text = " ".join(seg.text for seg in segments).strip()
            result = json.dumps({"text": text, "language": info.language})
            print(f"STT result [{info.language}]: {text[:80]}", flush=True)
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(result.encode())
        except Exception as e:
            print(f"STT error: {e}", flush=True)
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps({"error": str(e)}).encode())
    
    def log_message(self, format, *args):
        print(f"[HTTP] {args[0]}", flush=True)

if __name__ == "__main__":
    port = 9000
    print(f"Starting local faster-whisper STT server on :{port}", flush=True)
    # Pre-load model
    get_model()
    server = HTTPServer(("0.0.0.0", port), Handler)
    print(f"Server ready on :{port}", flush=True)
    server.serve_forever()
