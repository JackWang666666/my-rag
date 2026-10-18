"""Custom text splitters for different file types."""

from langchain_text_splitters import Language, RecursiveCharacterTextSplitter


def get_text_splitter(
    file_type: str,
    chunk_size: int = 1000,
    chunk_overlap: int = 200,
) -> RecursiveCharacterTextSplitter:
    """
    Get an appropriate text splitter based on file type.

    Args:
        file_type: File extension (e.g., '.py', '.md', '.pdf').
        chunk_size: Maximum characters per chunk.
        chunk_overlap: Overlap between chunks.

    Returns:
        A configured RecursiveCharacterTextSplitter instance.
    """
    # Map file extensions to LangChain Language enum
    language_map = {
        ".py": Language.PYTHON,
        ".js": Language.JS,
        ".ts": Language.TS,
        ".java": Language.JAVA,
        ".go": Language.GO,
        ".rs": Language.RUST,
        ".cpp": Language.CPP,
        ".c": Language.C,
        ".rb": Language.RUBY,
        ".php": Language.PHP,
    }

    file_type = file_type.lower()

    if file_type in language_map:
        # Use language-aware splitting for code files
        return RecursiveCharacterTextSplitter.from_language(
            language=language_map[file_type],
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
        )
    elif file_type == ".md":
        # Markdown-aware splitting
        return RecursiveCharacterTextSplitter(
            separators=[
                "\n# ",
                "\n## ",
                "\n### ",
                "\n#### ",
                "\n\n",
                "\n",
                " ",
                "",
            ],
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
        )
    elif file_type in (".csv", ".xlsx", ".xls"):
        # For tabular data, split by rows
        return RecursiveCharacterTextSplitter(
            separators=["\n\n", "\n", " ", ""],
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
        )
    else:
        # Default splitter for general text (PDF, DOCX, TXT, etc.)
        return RecursiveCharacterTextSplitter(
            separators=["\n\n", "\n", ". ", " ", ""],
            chunk_size=chunk_size,
            chunk_overlap=chunk_overlap,
        )
