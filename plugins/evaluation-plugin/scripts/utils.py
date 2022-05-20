import collections
import copy
import gzip
import json
import os
import random
import sys

import pandas as pd

import script


def read_id2feats(paths):
    id2feats = {}

    for path in paths:
        path = os.path.join(path, "features")
        for folder in os.listdir(path):
            if os.path.isdir(os.path.join(path, folder)) and not folder.startswith("Context.kt") and not folder.startswith("Debt.kt"):
                for fname in os.listdir(os.path.join(path, folder)):
                    session_path = os.path.join(path, folder, fname)
                    if os.path.isfile(session_path) and fname.endswith(".gz"):
                        with gzip.open(session_path) as gz:
                            obj = json.loads(gz.read())

                            assert len(obj) == 1
                            session_id = obj[0]["common"]["context"].get("ml_ctx_cce_sessionUid")
                            if not session_id:
                                continue

                            assert session_id not in id2feats
                            id2feats[session_id] = obj

                            if len(id2feats) % 100 == 0:
                                print(f"Read {len(id2feats)} feats", file=sys.stderr)

    print(f"Read {len(id2feats)} feats", file=sys.stderr)
    return id2feats


def read_sessions(paths):
    id2session = {}
    id2fname = {}

    for path in paths:
        path = os.path.join(path, "data/files/jsons")
        for fname in os.listdir(path):
            file_path = os.path.join(path, fname)
            if os.path.isfile(file_path) and fname.endswith(".json"):
                with open(file_path) as f:
                    obj = json.loads(f.read())
                    file_path = obj["filePath"]

                    for session in obj["sessions"]:
                        session_id = session["id"]
                        id2fname[session_id] = file_path
                        id2session[session_id] = session

                        if len(id2session) % 100 == 0:
                            print(f"Read {len(id2session)} sessions", file=sys.stderr)

    print(f"Read {len(id2session)} sessions", file=sys.stderr)
    return id2session, id2fname


def _make_key(suggestion):
    return suggestion["contributor"], suggestion["contributorKind"]

def calc_latencies(lookup):
    if len(lookup["suggestions"]) == 0:
        return {}


    pairs = [
        (suggestion["createdLatency"], _make_key(suggestion))
        for suggestion in lookup["suggestions"]
    ]
    pairs.sort()

    begin_ms = {}
    latency_ms = {}
    for ind, (latency, key) in enumerate(pairs):
        if ind == 0:
            begin_ms[key] = 0

        if ind > 0:
            prev_latency, prev_key = pairs[ind-1]
            if key != prev_key:
                if key in begin_ms:
                    # this key is not contiguous
                    return None

                latency_ms[prev_key] = prev_latency - begin_ms[prev_key]
                begin_ms[key] = prev_latency

        if ind == len(pairs) - 1:
            latency_ms[key] = latency - begin_ms[key]
    return latency_ms


def make_xy(id2feats, id2session):
    xs = []
    ys = []

    for session_id, feat in id2feats.items():
        if session_id not in id2session:
            print(f"Does not have session {session_id}, skipping...")
            continue
        session = id2session[session_id]
        text = session["expectedText"]
        assert len(session["_lookups"]) == 1
        latencies = calc_latencies(session["_lookups"][0])
        if not latencies:
            continue

        y = {
            (latencies[_make_key(suggestion)], _make_key(suggestion))
            for suggestion in session["_lookups"][0]["suggestions"]
            if suggestion["text"] == text
        }

        x = feat[0]["common"]["context"]

        xs.append(x)
        ys.append(y)

    return xs, ys


def make_data(xs, ys):
    data_rows = collections.defaultdict(list)
    labels_rows = collections.defaultdict(list)

    for (x, y) in zip(xs, ys):
        if not y:
            continue
        for feat in [
            "ml_ctx_kotlin_file_type",
            "ml_ctx_cce_sessionUid",
            "ml_ctx_common_is_in_line_beginning",
            "ml_ctx_common_case_sensitivity",
            "ml_ctx_common_is_after_dot",
            "ml_ctx_common_parent_1",
            "ml_ctx_common_parent_2",
            "ml_ctx_common_parent_3",
        ]:
            data_rows[feat].append(x.get(feat, ""))

        for feat in [
            "ml_ctx_common_line_num",
            "ml_ctx_common_col_num",
            "ml_ctx_common_indent_level",
        ]:
            data_rows[feat].append(float(x[feat]))

        labels_rows["target"].append(str(min(y)[1]))
        labels_rows["session_id"].append(x["ml_ctx_cce_sessionUid"])

    data = pd.DataFrame(data_rows)
    data.set_index("ml_ctx_cce_sessionUid", inplace=True)

    labels = pd.DataFrame(labels_rows)
    labels.set_index("session_id", inplace=True)

    return data, labels


def _split_by_fnames(data, labels, id2fname, fnames, rng):
    ids = [
        session_id
        for session_id, fname in id2fname.items()
        if fname in set(fnames) and session_id in data.index
    ]
    rng.shuffle(ids)
    return data.loc[ids], labels.loc[ids]


def split(data, labels, id2fname, seed=42):
    rng = random.Random(seed)

    fnames = sorted({fname for _, fname in id2fname.items()})
    shuffled = copy.deepcopy(fnames)
    rng.shuffle(shuffled)

    dev_cnt = test_cnt = int(len(fnames) * 0.2)
    train_cnt = len(fnames) - dev_cnt - test_cnt
    train_fnames = shuffled[:train_cnt]
    dev_fnames = shuffled[train_cnt:train_cnt + dev_cnt]
    test_fnames = shuffled[-test_cnt:]

    assert len(train_fnames) == train_cnt
    assert len(dev_fnames) == dev_cnt
    assert len(test_fnames) == test_cnt
    assert len(set(train_fnames + dev_fnames)) == train_cnt + dev_cnt
    assert len(set(train_fnames + dev_fnames + test_fnames)) == train_cnt + dev_cnt + test_cnt

    train = _split_by_fnames(data, labels, id2fname, train_fnames, rng)
    dev = _split_by_fnames(data, labels, id2fname, dev_fnames, rng)
    test = _split_by_fnames(data, labels, id2fname, test_fnames, rng)

    return train, dev, test


def make_preds(labels, preds):
    id2preds = {}
    for session_id, pred in zip(labels.index, preds):
        id2preds[session_id] = pred[0]
    return id2preds


def calc_metrics(id2preds, id2session):
    total_latency = 0
    total_found = 0
    cnt = len(id2preds)

    for session_id, pred in id2preds.items():
        session = id2session[session_id]   

        assert len(session["_lookups"]) == 1
        latencies = calc_latencies(session["_lookups"][0])
        assert latencies

        for kind, latency in latencies.items():
            if str(kind) == pred:
                total_latency += latency
                break

        text = session["expectedText"]
        total_found += text in [
            suggestion["text"]
            for suggestion in session["_lookups"][0]["suggestions"]
            if pred == str(_make_key(suggestion))
        ]

    return {
        "latency": total_latency / cnt,
        "recall": total_found / cnt,
    }


def print_current_metrics(id2preds, id2session):
    total_latency = 0
    total_found = 0
    cnt = len(id2preds)

    metrics = [ 
        script.ApproxRecall(delay_ms=50),
        script.ApproxRecall(delay_ms=100),
        script.ApproxRecall(delay_ms=150),
        script.ApproxRecall(delay_ms=200),
        script.MeanApproxLatency(delay_ms=50),
        script.MeanApproxLatency(delay_ms=100),
        script.MeanApproxLatency(delay_ms=150),
        script.MeanApproxLatency(delay_ms=200),
    ]
    for session_id, pred in id2preds.items():
        session = copy.deepcopy(id2session[session_id])
        assert len(session["_lookups"]) == 1
        session["latencies"] = calc_latencies(session["_lookups"][0])
        for metric in metrics:
            metric.update(session)

    for metric in metrics:
        metric.print()
