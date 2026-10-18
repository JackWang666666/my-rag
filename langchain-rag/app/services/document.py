"""Document parsing and chunking service."""

import os
from typing import List

from langchain_core.documents import Document

from app.core.config import settings
from app.utils.text_splitter import get_text_splitter


def parse_file(file_path: str, file_type: str) -> List[Document]:
    """
    Parse a file and split it into text chunks with metadata.

    Args:
        file_path: Path to the file to parse.
        file_type: File extension (e.g., '.pdf', '.docx', '.md').

    Returns:
        List of LangChain Document objects with page_content and metadata.
    """
    raw_text = _extract_text(file_path, file_type)

    if not raw_text.strip():
        return []

    splitter = get_text_splitter(
        file_type=file_type,
        chunk_size=settings.CHUNK_SIZE,
        chunk_overlap=settings.CHUNK_OVERLAP,
    )

    filename = os.path.basename(file_path)
    documents = splitter.create_documents(
        texts=[raw_text],
        metadatas=[{"source": filename, "file_type": file_type}],
    )

    return documents


def _extract_text(file_path: str, file_type: str) -> str:
    """
    Extract raw text from a file based on its type.

    Args:
        file_path: Path to the file.
        file_type: File extension.

    Returns:
        Extracted text content.
    """
    file_type = file_type.lower()

    if file_type == ".pdf":
        return _extract_pdf(file_path)
    elif file_type == ".docx":
        return _extract_docx(file_path)
    elif file_type in (".md", ".txt", ".log"):
        return _extract_plain_text(file_path)
    elif file_type == ".csv":
        return _extract_csv(file_path)
    elif file_type in (".xlsx", ".xls"):
        return _extract_excel(file_path)
    elif file_type in (".py", ".js", ".ts", ".java", ".go", ".rs", ".cpp", ".c", ".h", ".rb", ".php"):
        return _extract_plain_text(file_path)
    else:
        # Fallback: try to read as plain text
        return _extract_plain_text(file_path)


def _extract_pdf(file_path: str) -> str:
    """Extract text from a PDF file."""
    from pypdf import PdfReader

    reader = PdfReader(file_path)
    text_parts = []
    for page in reader.pages:
        page_text = page.extract_text()
        if page_text:
            text_parts.append(page_text)
    return "\n\n".join(text_parts)


def _extract_docx(file_path: str) -> str:
    """Extract text from a DOCX file."""
    from docx import Document as DocxDocument

    doc = DocxDocument(file_path)
    paragraphs = [para.text for para in doc.paragraphs if para.text.strip()]
    return "\n\n".join(paragraphs)


def _extract_plain_text(file_path: str) -> str:
    """Read a plain text file."""
    with open(file_path, "r", encoding="utf-8", errors="ignore") as f:
        return f.read()


def _extract_csv(file_path: str) -> str:
    """Extract text from a CSV file."""
    import pandas as pd

    df = pd.read_csv(file_path)
    return df.to_string(index=False)


def _extract_excel(file_path: str) -> str:
    """Extract text from an Excel file."""
    import pandas as pd

    dfs = pd.read_excel(file_path, sheet_name=None)
    text_parts = []
    for sheet_name, df in dfs.items():
        text_parts.append(f"=== Sheet: {sheet_name} ===")
        text_parts.append(df.to_string(index=False))
    return "\n\n".join(text_parts)
