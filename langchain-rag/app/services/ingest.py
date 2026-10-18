"""Document ingestion: parse -> chunk -> embed -> Milvus + SQLite + Neo4j.

Ingestion runs as a FastAPI background task. The upload endpoint returns a
document row with ``status="processing"`` immediately and the frontend polls
``GET /api/documents/{id}`` until it flips to ``completed`` or ``failed``.
Previously the whole pipeline ran inside the request, so a large PDF or a project
ZIP held the connection open for minutes (and the `processing` status the
frontend already modelled was never actually produced).
"""

import logging
import os
import shutil
import zipfile
from typing import Any, Dict, List, Optional, Set, Tuple

from app.core import store
from app.core.config import settings
from app.services.document import parse_file
from app.services.embedding import get_embeddings
from app.services.graph import extract_entities, store_triples
from app.services.retrieval import insert_documents
from app.utils.gitignore import is_ignored, parse_gitignore
from app.utils.text_splitter import get_text_splitter

logger = logging.getLogger(__name__)

SUPPORTED_TYPES: Set[str] = {
    ".pdf", ".docx", ".md", ".txt", ".csv", ".xlsx", ".xls",
    ".py", ".js", ".ts", ".java", ".go", ".rs", ".cpp", ".c",
    ".h", ".rb", ".php", ".log",
}

# Default file extensions to scan for code projects
DEFAULT_CODE_EXTENSIONS: Set[str] = {
    ".py", ".js", ".ts", ".tsx", ".jsx", ".java", ".go", ".rs",
    ".cpp", ".c", ".h", ".rb", ".php", ".swift", ".kt", ".scala",
    ".sql", ".yaml", ".yml", ".json", ".toml", ".xml", ".md",
    ".txt", ".html", ".css", ".scss",
}

# Directories to always skip
SKIP_DIRS: Set[str] = {
    "node_modules", ".git", "__pycache__", "venv", ".venv",
    "dist", "build", "target", ".idea", ".vscode",
    "env", ".env", ".tox", ".mypy_cache", ".pytest_cache",
    "site-packages", ".next", "out", "coverage",
}

# File extensions to always skip (binary files)
SKIP_EXTENSIONS: Set[str] = {
    ".class", ".pyc", ".pyo", ".so", ".dll", ".dylib",
    ".exe", ".bin", ".obj", ".o", ".a", ".lib",
    ".jar", ".war", ".ear", ".zip", ".tar", ".gz",
    ".png", ".jpg", ".jpeg", ".gif", ".bmp", ".ico",
    ".svg", ".woff", ".woff2", ".ttf", ".eot",
    ".mp3", ".mp4", ".avi", ".mov", ".wav",
    ".pdf", ".doc", ".docx", ".xls", ".xlsx",
    ".lock", ".sum",
}


# --- Shared helpers -------------------------------------------------------


async def _embed_in_batches(texts: List[str]) -> List[List[float]]:
    """Embed texts in batches.

    A project ZIP can produce thousands of chunks; one aembed_documents call
    with all of them either blows past the provider's request limit or times out.
    """
    embeddings_model = get_embeddings()
    batch_size = max(1, settings.EMBEDDING_BATCH_SIZE)
    vectors: List[List[float]] = []
    for start in range(0, len(texts), batch_size):
        batch = texts[start:start + batch_size]
        vectors.extend(await embeddings_model.aembed_documents(batch))
    return vectors


async def _extract_graph(kb_id: str, texts: List[str]) -> None:
    """Best-effort entity extraction into Neo4j.

    Bounded by GRAPH_MAX_CHUNKS: unbounded means one LLM call per chunk, but the
    old hardcoded limit of 3 chunks meant the graph was near-empty for anything
    bigger than a memo.
    """
    limit = max(0, settings.GRAPH_MAX_CHUNKS)
    if limit == 0:
        return
    for chunk_text in texts[:limit]:
        try:
            triples = await extract_entities(chunk_text)
            if triples:
                store_triples(kb_id, triples)
        except Exception as e:
            logger.warning("Entity extraction failed for a chunk: %s", e)


async def _persist(
    kb_id: str,
    doc_id: str,
    texts: List[str],
    metadatas: List[Dict[str, Any]],
) -> int:
    """Embed, write to Milvus, mirror into SQLite, extract graph. Returns count."""
    store.set_document_stage(doc_id, "embedding")
    embeddings = await _embed_in_batches(texts)
    chunk_ids = [store.new_id() for _ in texts]

    insert_documents(
        kb_id=kb_id,
        chunk_ids=chunk_ids,
        document_id=doc_id,
        texts=texts,
        embeddings=embeddings,
        metadatas=metadatas,
    )

    store.insert_chunks(kb_id, doc_id, [
        {
            "id": chunk_ids[i],
            "chunk_index": metadatas[i].get("chunk_index", i),
            "source": metadatas[i].get("source", ""),
            "text": texts[i],
            "metadata": metadatas[i],
        }
        for i in range(len(texts))
    ])

    store.set_document_stage(doc_id, "graph")
    await _extract_graph(kb_id, texts)
    return len(texts)


# --- Single file ---------------------------------------------------------


async def ingest_file(kb_id: str, doc_id: str, file_path: str, file_ext: str,
                      filename: str) -> None:
    """Background task: ingest one uploaded file."""
    try:
        store.set_document_stage(doc_id, "parsing")
        chunks = parse_file(file_path, file_ext)
        if not chunks:
            store.mark_document_failed(doc_id, "No content could be extracted from the file")
            _remove(file_path)
            return

        texts = [c.page_content for c in chunks]
        metadatas = [
            {
                "source": filename,
                "file_type": file_ext,
                "chunk_index": i,
                "document_id": doc_id,
            }
            for i in range(len(texts))
        ]

        count = await _persist(kb_id, doc_id, texts, metadatas)
        store.mark_document_completed(doc_id, count)
        logger.info("Ingested %s (%d chunks) into kb %s", filename, count, kb_id)

    except Exception as e:
        logger.exception("Ingestion failed for document %s", doc_id)
        store.mark_document_failed(doc_id, str(e))
        _remove(file_path)


def _remove(path: str) -> None:
    try:
        if path and os.path.exists(path):
            os.remove(path)
    except OSError:
        pass


# --- Project ZIP --------------------------------------------------------


def _is_binary_file(file_path: str) -> bool:
    """Check if a file appears to be binary by reading the first chunk."""
    try:
        with open(file_path, "rb") as f:
            if b"\x00" in f.read(8192):
                return True
    except (IOError, OSError):
        return True
    return False


def safe_extract(zip_path: str, extract_dir: str) -> None:
    """Extract a ZIP, refusing entries that escape the target directory.

    `ZipFile.extractall` honours `../` and absolute paths in member names, so an
    attacker-supplied archive could overwrite arbitrary files ("zip slip").
    """
    extract_root = os.path.realpath(extract_dir)
    with zipfile.ZipFile(zip_path, "r") as zf:
        for member in zf.infolist():
            name = member.filename
            if not name or name.endswith("/"):
                continue
            if os.path.isabs(name) or name.startswith("/") or ".." in name.split("/"):
                raise ValueError("Unsafe path in ZIP archive: " + name)
            target = os.path.realpath(os.path.join(extract_root, name))
            if not (target == extract_root or target.startswith(extract_root + os.sep)):
                raise ValueError("Unsafe path in ZIP archive: " + name)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            with zf.open(member) as src, open(target, "wb") as dst:
                shutil.copyfileobj(src, dst)


def parse_extension_filter(file_extensions: Optional[str]) -> Set[str]:
    """`".py,js"` -> `{".py", ".js"}`. Empty input -> the code defaults."""
    if not file_extensions:
        return DEFAULT_CODE_EXTENSIONS
    exts = set()
    for ext in file_extensions.split(","):
        ext = ext.strip().lower()
        if not ext:
            continue
        exts.add(ext if ext.startswith(".") else "." + ext)
    return exts or DEFAULT_CODE_EXTENSIONS


def _generate_file_tree(project_root: str, files: List[str]) -> str:
    """Generate a tree-like representation of the project structure."""
    root_name = os.path.basename(project_root) or "project"
    lines = [root_name + "/"]
    for file_path in sorted(files):
        parts = file_path.replace("\\", "/").split("/")
        lines.append("  " * (len(parts) - 1) + parts[-1])
    return "\n".join(lines)


def scan_project_files(project_root: str, allowed_extensions: Set[str]) -> List[str]:
    """Relative paths of the code files worth indexing."""
    rules = parse_gitignore(project_root)
    code_files: List[str] = []

    for dirpath, dirnames, filenames in os.walk(project_root):
        rel_dir = os.path.relpath(dirpath, project_root).replace("\\", "/")
        if rel_dir == ".":
            rel_dir = ""

        dirnames[:] = [
            d for d in dirnames
            if d not in SKIP_DIRS
            and not d.startswith(".")
            and not is_ignored((rel_dir + "/" + d).lstrip("/"), rules, is_dir=True)
        ]

        for filename in filenames:
            ext = os.path.splitext(filename)[1].lower()
            if ext in SKIP_EXTENSIONS or ext not in allowed_extensions:
                continue

            rel_path = (rel_dir + "/" + filename).lstrip("/")
            if is_ignored(rel_path, rules):
                continue
            if _is_binary_file(os.path.join(dirpath, filename)):
                continue

            code_files.append(rel_path)

    return code_files


def _chunk_project(
    project_root: str, code_files: List[str], doc_id: str
) -> Tuple[List[str], List[Dict[str, Any]]]:
    """Chunk every code file, prefixing each chunk with its file path."""
    texts: List[str] = []
    metadatas: List[Dict[str, Any]] = []

    file_tree = _generate_file_tree(project_root, code_files)
    texts.append("Project Structure:\n\n" + file_tree)
    metadatas.append({
        "source": "project_structure",
        "file_type": "tree",
        "chunk_index": 0,
        "document_id": doc_id,
        "is_project_structure": True,
    })

    chunk_index = 1
    for rel_path in code_files:
        abs_path = os.path.join(project_root, rel_path)
        ext = os.path.splitext(rel_path)[1].lower()
        try:
            with open(abs_path, "r", encoding="utf-8", errors="ignore") as f:
                file_content = f.read()
            if not file_content.strip():
                continue

            splitter = get_text_splitter(
                file_type=ext,
                chunk_size=settings.CHUNK_SIZE,
                chunk_overlap=settings.CHUNK_OVERLAP,
            )
            chunks = splitter.create_documents(
                texts=[file_content],
                metadatas=[{"source": rel_path, "file_type": ext}],
            )

            for chunk in chunks:
                texts.append("File: %s\n\n%s" % (rel_path, chunk.page_content))
                metadatas.append({
                    "source": rel_path,
                    "file_type": ext,
                    "chunk_index": chunk_index,
                    "document_id": doc_id,
                })
                chunk_index += 1
        except Exception as e:
            logger.debug("Skipping unreadable file %s: %s", rel_path, e)
            continue

    return texts, metadatas


async def ingest_project(
    kb_id: str,
    doc_id: str,
    zip_path: str,
    temp_dir: str,
    file_extensions: Optional[str] = None,
) -> None:
    """Background task: extract a project ZIP and ingest every code file."""
    try:
        store.set_document_stage(doc_id, "parsing")
        extract_dir = os.path.join(temp_dir, "extracted")
        os.makedirs(extract_dir, exist_ok=True)

        try:
            safe_extract(zip_path, extract_dir)
        except zipfile.BadZipFile:
            store.mark_document_failed(doc_id, "Invalid ZIP file")
            return
        except ValueError as e:
            store.mark_document_failed(doc_id, str(e))
            return

        # Handle the common "everything inside one top-level folder" layout.
        items = os.listdir(extract_dir)
        if len(items) == 1 and os.path.isdir(os.path.join(extract_dir, items[0])):
            project_root = os.path.join(extract_dir, items[0])
        else:
            project_root = extract_dir

        code_files = scan_project_files(
            project_root, parse_extension_filter(file_extensions)
        )
        if not code_files:
            store.mark_document_failed(
                doc_id, "No code files found in the ZIP archive matching the criteria"
            )
            return

        texts, metadatas = _chunk_project(project_root, code_files, doc_id)
        if len(texts) <= 1:
            store.mark_document_failed(
                doc_id, "No content could be extracted from the project files"
            )
            return

        count = await _persist(kb_id, doc_id, texts, metadatas)
        store.mark_document_completed(doc_id, count)
        logger.info(
            "Imported project into kb %s: %d files, %d chunks",
            kb_id, len(code_files), count,
        )

    except Exception as e:
        logger.exception("Project import failed for document %s", doc_id)
        store.mark_document_failed(doc_id, str(e))
    finally:
        shutil.rmtree(temp_dir, ignore_errors=True)
