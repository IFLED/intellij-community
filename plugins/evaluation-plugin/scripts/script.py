import collections
import json
import os
import sys


def get_variants(session, delay_ms=None, latency_name="createdLatency"):
    assert len(session["_lookups"]) == 1
    lookup = session["_lookups"][0]

    if delay_ms:
        latencies = [
            suggestion[latency_name]
            for suggestion in lookup["suggestions"]
        ]

        if not latencies:
            return []

        min_latency = min(latencies)

        return [
            suggestion["text"]
            for suggestion in lookup["suggestions"]
            if suggestion[latency_name] <= min_latency + delay_ms
        ]

    return [
        suggestion["text"] for suggestion in lookup["suggestions"]
    ]


class Metric:
    def update(session):
        raise NotImplementedError()

    def print(session):
        raise NotImplementedError()


class Recall(Metric):
    def __init__(self):
        self._found = 0
        self._count = 0

    def update(self, session):
        text = session["expectedText"]
        variants = get_variants(session)

        found = text in variants

        self._found += found
        self._count += 1

    def print(self):
        ratio = self._found / self._count if self._count else 0
        print(f"recall: {ratio:.3f} ({self._found} / {self._count})")


class ContributorKindRecall(Metric):
    def __init__(self):
        self._kinds = collections.Counter()
        self._count = 0

    def update(self, session):
        text = session["expectedText"]
        # import ipdb; ipdb.set_trace(context=15)

        lookups = session["_lookups"]
        assert len(lookups) == 1

        for suggestion in lookups[0]["suggestions"]:
            kind = suggestion["contributorKind"]
            hypo = suggestion["text"]
            self._kinds[kind] += text == hypo

        self._count += 1

    def print(self):
        print(f"recall by contributor kinds:")
        for kind, cnt in self._kinds.most_common():
            ratio = cnt / self._count if self._count else 0
            print(f"    {ratio:.3f} ({cnt} / {self._count}) - {kind}")


class ApproxRecall(Metric):
    def __init__(self, delay_ms):
        self._found = 0
        self._count = 0
        self._delay_ms = delay_ms

    def update(self, session):
        text = session["expectedText"]
        variants = get_variants(session, self._delay_ms)

        found = text in variants

        self._found += found
        self._count += 1

    def print(self):
        ratio = self._found / self._count if self._count else 0
        print(f"approx recall ({self._delay_ms}ms): {ratio:.3f} ({self._found} / {self._count})")


def is_contiguous(session, latency_name):
        lookups = session["_lookups"]
        assert len(lookups) == 1

        if len(lookups[0]["suggestions"]) == 0:
            return True

        pairs = [
            (suggestion[latency_name], (suggestion["contributor"], suggestion["contributorKind"]))
            for suggestion in lookups[0]["suggestions"]
        ]
        pairs.sort()
        kinds = [
            pair[1]
            for ind, pair in enumerate(pairs)
            if ind == 0 or pair[1] != pairs[ind-1][1]
        ]
        counter = collections.Counter(kinds)

        # if latency_name == "createdLatency" and counter.most_common(1)[0][1] == 1:
        #     # for pair in pairs:
        #     #     print(pair)
        #     ## import pprint
        #     ## pprint.pprint( sorted([(item["createdLatency"], item["text"], item["contributorKind"]) for item in lookups[0]["suggestions"]]))
        #     ## import pprint; pprint.pprint( sorted([(item["createdLatency"], item["resultsetLatency"], item["lookupLatency"], item["indicatorLatency"], item["renderedLatency"], item["contributorKind"], item["text"]) for item in lookups[0]["suggestions"]]), width=300)
        #     ## import pprint; pprint.pprint( sorted([(item["createdLatency"], item["resultsetLatency"], item["lookupLatency"], item["contributorKind"], item["text"]) for item in lookups[0]["suggestions"]]), width=300)
        #     import pprint; pprint.pprint( sorted([(item["createdLatency"], item["resultsetLatency"], item["lookupLatency"], item["contributorKind"], item["text"]) for item in lookups[0]["suggestions"]]), width=300)
        #     import ipdb; ipdb.set_trace(context=15)

        return counter.most_common(1)[0][1] == 1


class ContiguousKinds(Metric):
    def __init__(self, latency_name):
        self._good = 0
        self._count = 0
        self._latency_name = latency_name

    def update(self, session):
        self._count += 1
        self._good += is_contiguous(session, self._latency_name)

    def print(self):
        ratio = self._good / self._count if self._count else 0
        print(f"contiguous ({self._latency_name}): {ratio:.3f} ({self._good} / {self._count})")


class MeanPopupLatency(Metric):
    def __init__(self):
        self._total_popup_latency = 0
        self._count = 0

    def update(self, session):
        lookups = session["_lookups"]
        assert len(lookups) == 1
        self._total_popup_latency += lookups[0]["popupLatency"]
        self._count += 1

    def print(self):
        ratio = self._total_popup_latency / self._count if self._count else 0
        print(f"mean popup latency: {ratio:.3f} ({self._total_popup_latency} / {self._count})")


class MeanOptimisticOracleLatency(Metric):
    def __init__(self, label, agg):
        self._total_latency = 0
        self._count = 0
        self._skipped = 0
        self._label = label
        self._agg = agg

    def update(self, session):
        lookups = session["_lookups"]
        assert len(lookups) == 1

        latency = self._calc_latency(session["expectedText"], lookups[0])
        if latency:
            self._total_latency += latency
            self._count += 1
        else:
            self._skipped += 1

    def _calc_latency(self, text, lookup):
        latencies = [
            suggestion["createdLatency"]
            for suggestion in lookup["suggestions"]
            if text == suggestion["text"]
        ]
        if latencies:
            return self._agg(latencies)

    def print(self):
        ratio = self._total_latency / self._count if self._count else 0
        print(f"{self._label} optimistic oracle latency: {ratio:.3f} ({self._total_latency:.3f} / {self._count}, skipped = {self._skipped})")


class MeanReorderOracleLatency(Metric):
    def __init__(self, label, agg):
        self._total_latency = 0
        self._count = 0
        self._skipped = 0
        self._label = label
        self._agg = agg

    def update(self, session):
        lookups = session["_lookups"]
        assert len(lookups) == 1

        latency = self._calc_latency(session["expectedText"], lookups[0])
        if latency:
            self._total_latency += latency
            self._count += 1
        else:
            self._skipped += 1

    def _calc_latency(self, text, lookup):
        if len(lookup["suggestions"]) == 0:
            return None

        def make_key(suggestion):
            return suggestion["contributor"], suggestion["contributorKind"]

        pairs = [
            (suggestion["createdLatency"], make_key(suggestion))
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
                        # this key is not contiguous, skipping
                        return None

                    latency_ms[prev_key] = prev_latency - begin_ms[prev_key]
                    begin_ms[key] = prev_latency

            if ind == len(pairs) - 1:
                latency_ms[key] = latency - begin_ms[key]

        latencies = [
            latency_ms[make_key(suggestion)]
            for suggestion in lookup["suggestions"]
            if text == suggestion["text"]
        ]
        if latencies:
            return self._agg(latencies)
        else:
            return None

    def print(self):
        ratio = self._total_latency / self._count if self._count else 0
        print(f"{self._label} reorder oracle latency: {ratio:.3f} ({self._total_latency:.3f} / {self._count}, skipped = {self._skipped})")


class MeanApproxLatency(Metric):
    def __init__(self, delay_ms):
        self._total_latency = 0
        self._count = 0
        self._skipped = 0
        self._delay_ms = delay_ms

    def update(self, session):
        lookups = session["_lookups"]
        assert len(lookups) == 1

        latency = self._calc_latency(session["expectedText"], lookups[0])
        if latency:
            self._total_latency += latency
            self._count += 1
        else:
            self._skipped += 1

    def _calc_latency(self, text, lookup):
        latencies = [
            suggestion["createdLatency"]
            for suggestion in lookup["suggestions"]
        ]

        if not latencies:
            return

        min_latency = min(latencies)

        return min_latency + self._delay_ms

    def print(self):
        ratio = self._total_latency / self._count if self._count else 0
        print(f"mean approx latency ({self._delay_ms}ms): {ratio:.3f} ({self._total_latency:.3f} / {self._count}, skipped = {self._skipped})")


def make_all_metrics():
    metrics = [
        Recall(),
        ApproxRecall(delay_ms=50),
        ApproxRecall(delay_ms=100),
        ApproxRecall(delay_ms=200),
        ContiguousKinds("createdLatency"),
        ContiguousKinds("resultsetLatency"),
        ContiguousKinds("indicatorLatency"),
        ContiguousKinds("lookupLatency"),
        ContiguousKinds("renderedLatency"),
        MeanPopupLatency(),
        MeanOptimisticOracleLatency("min", min),
        MeanOptimisticOracleLatency("avg", lambda xs: sum(xs) / max(len(xs), 1)),
        MeanOptimisticOracleLatency("max", max),
        MeanReorderOracleLatency("min", min),
        MeanReorderOracleLatency("avg", lambda xs: sum(xs) / max(len(xs), 1)),
        MeanReorderOracleLatency("max", max),
        MeanApproxLatency(delay_ms=0),
        ContributorKindRecall(),
    ]
    return metrics


def process_file(path, agg_metrics=None):
    with open(path) as f:
        obj = json.load(f)

    metrics = make_all_metrics()

    print(obj["filePath"])

    for session in obj["sessions"]:
        for metric in metrics:
            metric.update(session)
        if agg_metrics:
            for metric in agg_metrics:
                metric.update(session)
        # variants = get_variants(session)
        # text = session["expectedText"]

        # print(session["id"], text, session["_lookups"][0]["prefix"], len(variants), text in variants)

    for metric in metrics:
        print("  ", end="")
        metric.print()


def process_dir(path):
    agg_metrics = make_all_metrics()
    for root, dirs, files in os.walk(path):
        for fname in files:
            process_file(os.path.join(root, fname), agg_metrics)

    print("aggregated metrics for all files:")
    for metric in agg_metrics:
        print("  ", end="")
        metric.print()


def main():
    print(sys.argv, file=sys.stderr)
    path = sys.argv[1]

    if os.path.isfile(path):
        process_file(path)
    else:
        process_dir(path)


if __name__ == "__main__":
    main()
