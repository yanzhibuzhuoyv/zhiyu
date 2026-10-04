"""使用现有本地嵌入模型验证真实语义向量，不读取私人文档。"""

import argparse
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path

from app.rag import RagService


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, help="已有本地 SentenceTransformer 模型目录")
    options = parser.parse_args()
    with tempfile.TemporaryDirectory(prefix="zhiyu-semantic-") as directory:
        data_dir = Path(directory)
        service = RagService(data_dir, embedding_model=options.model)
        service.ingest("alice", "leave.txt", "员工病假申请需直属主管审批，身体不舒服可提交医疗证明。".encode())
        service.ingest("alice", "pets.txt", "宠物猫喜欢晒太阳和玩逗猫棒，饮食应保持规律。".encode())
        service.ingest("bob", "private.txt", "另一个用户的私人病假资料。".encode())
        results = service.search("alice", "身体不适没法来上班，该由谁同意？", top_k=2)
        assert results and results[0]["name"] == "leave.txt", results
        assert all(item["name"] != "private.txt" for item in results)
        with closing(sqlite3.connect(data_dir / "rag.sqlite3")) as database:
            vectors = database.execute("SELECT COUNT(*) FROM embeddings").fetchone()[0]
        assert vectors == 2
        repeated = service.search("alice", "身体不适没法来上班，该由谁同意？", top_k=2)
        assert repeated == results
        print(f"Real semantic smoke passed: mode={service.mode}, persisted_vectors={vectors}, top_source={results[0]['name']}, owner isolation, cache reuse")


if __name__ == "__main__":
    main()
