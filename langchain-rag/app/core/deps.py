"""Singleton instances for shared dependencies."""

from typing import Optional

from neo4j import GraphDatabase
from pymilvus import connections

from app.core.config import settings

# Global state for singleton connections
_milvus_connected: bool = False
_neo4j_driver: Optional[object] = None


def get_milvus_connection() -> None:
    """Ensure Milvus connection is established (singleton)."""
    global _milvus_connected
    if not _milvus_connected:
        connections.connect(
            alias="default",
            host=settings.MILVUS_HOST,
            port=settings.MILVUS_PORT,
        )
        _milvus_connected = True


def get_neo4j_driver():
    """Get or create the Neo4j driver (singleton)."""
    global _neo4j_driver
    if _neo4j_driver is None:
        _neo4j_driver = GraphDatabase.driver(
            settings.NEO4J_URI,
            auth=(settings.NEO4J_USER, settings.NEO4J_PASSWORD),
        )
    return _neo4j_driver


def close_neo4j_driver() -> None:
    """Close the Neo4j driver connection."""
    global _neo4j_driver
    if _neo4j_driver is not None:
        _neo4j_driver.close()
        _neo4j_driver = None


def disconnect_milvus() -> None:
    """Disconnect from Milvus."""
    global _milvus_connected
    if _milvus_connected:
        connections.disconnect(alias="default")
        _milvus_connected = False


def get_settings() -> "Settings":
    """Return the global settings instance."""
    return settings
