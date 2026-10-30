"""Render synthetic baseline outputs for human review, without assigning a quality score."""
import argparse
import json
from pathlib import Path


def render(request_path: Path, response_path: Path, output: Path):
    requests = [json.loads(line) for line in request_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    rows = [json.loads(line) for line in response_path.read_text(encoding="utf-8").splitlines() if line.strip()]
    responses = {row["id"]: row for row in rows}
    if len(responses) != len(rows) or {item["id"] for item in requests} != set(responses):
        raise ValueError("Missing, duplicate, or unexpected evaluation case")
    lines = ["# MEL Chat 日本語RP・小説・記憶の基準出力", "",
             "**品質は未採点。zetaとの比較は未実施。これは完成判定ではありません。**", "",
             "Linux CPU・推奨Qwen3 4B Q4_K_M・文脈4096で、アプリのRepositoryが組み立てた入力を実行しました。",
             "時間はCI環境の測定値です。Galaxyでの速度・発熱を示すものではありません。",
             "検索や要約の処理時間は含みません。長期記憶ケースは用意した要約と原文を使い、要約モデルの品質は評価していません。", ""]
    for case in requests:
        row = responses[case["id"]]
        if not row["text"].strip():
            raise ValueError(f"Empty output: {case['id']}")
        user = next(item["content"] for item in reversed(case["messages"]) if item["role"] == "user")
        lines += [f"## {case['id']}", "", f"確認点：{case['criteria']}", "", f"投稿：{user}", "",
                  "生成結果：", "", row["text"], "",
                  f"入力 {row['input_tokens']} tokens / 出力 {row['output_tokens']} tokens / "
                  f"最初の文字 {row['first_text_seconds']:.2f}秒 / 全生成 {row['elapsed_seconds']:.2f}秒", "",
                  "人による採点：未実施", ""]
    output.write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("requests", type=Path)
    parser.add_argument("responses", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    render(args.requests, args.responses, args.output)
