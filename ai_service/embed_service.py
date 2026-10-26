"""Embedding 边车：文本 -> 向量，只此一件事。

契约（docs/design-docs/0005-search.md §4.3）：
- `POST /embed {texts: [...], kind: "query"|"passage"}` -> `{vectors: [[384 floats], ...], modelId}`
- 前缀由本服务强制：query 加 `query: `、passage 加 `passage: `，调用方无法绕过
- `GET /health` -> `{status, dims, modelId}`

只依赖本机已安装的 onnxruntime / tokenizers / numpy / pydantic / fastapi / uvicorn
（pip 在本机不可用，不要新增依赖）。
"""
import os
import sys

import numpy as np
import onnxruntime as ort
import uvicorn
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from tokenizers import Tokenizer

MODEL_DIR = os.environ.get(
    "EMBED_MODEL_DIR",
    os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), ".runtime", "models", "multilingual-e5-small"),
)
MODEL_ID = "multilingual-e5-small"
DIMS = 384
MAX_TOKENS = 512
PORT = int(os.environ.get("EMBED_PORT", "8025"))

PREFIXES = {"query": "query: ", "passage": "passage: "}


class BadInput(ValueError):
    """调用方的问题（kind 不对、文本已带前缀）——映射成 400，与内部错误区分开。"""


def build_input(kind, text):
    """前缀在这里强制，而且只在这里：调用方给原文 + kind，不可能拼错也不会拼两遍。"""
    if kind not in PREFIXES:
        raise BadInput("unknown kind: %s (expected query|passage)" % kind)
    if text.startswith("query: ") or text.startswith("passage: "):
        raise BadInput("text already carries an e5 prefix; pass the raw text and a kind")
    return PREFIXES[kind] + text


class EmbedRequest(BaseModel):
    texts: list[str]
    kind: str


class EmbedResponse(BaseModel):
    vectors: list[list[float]]
    modelId: str


_tokenizer = None
_session = None
_input_names = None


def _load():
    global _tokenizer, _session, _input_names
    if _session is not None:
        return
    model_path = os.path.join(MODEL_DIR, "model.onnx")
    tokenizer_path = os.path.join(MODEL_DIR, "tokenizer.json")
    for path in (model_path, tokenizer_path):
        if not os.path.exists(path):
            raise RuntimeError("missing model artifact: %s" % path)
    _tokenizer = Tokenizer.from_file(tokenizer_path)
    _tokenizer.enable_truncation(max_length=MAX_TOKENS)
    # 一批文本长度不同，必须补齐，否则 encode_batch 出参是 ragged，numpy 组数组直接报错。
    _tokenizer.enable_padding()
    options = ort.SessionOptions()
    options.intra_op_num_threads = 2
    _session = ort.InferenceSession(model_path, sess_options=options, providers=["CPUExecutionProvider"])
    _input_names = {i.name for i in _session.get_inputs()}


def encode(texts, kind):
    _load()
    inputs = [build_input(kind, t) for t in texts]
    encodings = _tokenizer.encode_batch(inputs)
    input_ids = np.array([e.ids for e in encodings], dtype=np.int64)
    attention = np.array([e.attention_mask for e in encodings], dtype=np.int64)
    feed = {"input_ids": input_ids, "attention_mask": attention}
    if "token_type_ids" in _input_names:
        feed["token_type_ids"] = np.zeros_like(input_ids)
    hidden = _session.run(None, feed)[0]
    mask = attention[..., None].astype(np.float32)
    summed = (hidden * mask).sum(axis=1)
    counts = np.clip(mask.sum(axis=1), 1e-9, None)
    mean = summed / counts
    norms = np.clip(np.linalg.norm(mean, axis=1, keepdims=True), 1e-12, None)
    return (mean / norms).astype(np.float32).tolist()


app = FastAPI(title="oj embedding sidecar")


@app.get("/health")
def health():
    _load()
    return {"status": "ok", "dims": DIMS, "modelId": MODEL_ID}


@app.post("/embed", response_model=EmbedResponse)
def embed(req: EmbedRequest):
    if not req.texts:
        raise HTTPException(status_code=400, detail="texts must not be empty")
    try:
        vectors = encode(req.texts, req.kind)
    except BadInput as exc:
        raise HTTPException(status_code=400, detail=str(exc))
    except Exception as exc:  # 边车不吞错，让 judge 看到明确的 5xx
        raise HTTPException(status_code=500, detail=str(exc))
    return EmbedResponse(vectors=vectors, modelId=MODEL_ID)


if __name__ == "__main__":
    if "--selfcheck" in sys.argv:
        _load()
        print("dims=%d model=%s" % (len(encode(["hello"], "query")[0]), MODEL_ID))
        sys.exit(0)
    uvicorn.run(app, host="127.0.0.1", port=PORT, log_level="warning")
