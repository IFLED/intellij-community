import collections
import json
import os
import sys

import utils


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


class ContiguousRecall(Metric):
    def __init__(self):
        self._found = 0
        self._count = 0
        self._skipped = 0

    def update(self, session):
        if not session["is_contiguous"]:
            self._skipped += 1
            return

        text = session["expectedText"]
        variants = get_variants(session)

        found = text in variants

        self._found += found
        self._count += 1

    def print(self):
        ratio = self._found / self._count if self._count else 0
        print(f"contiguous recall (skipped = {self._skipped}): {ratio:.3f} ({self._found} / {self._count})")


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


class ContributorKindDuration(Metric):
    def __init__(self):
        self._kinds = collections.Counter()
        self._latencies = {}
        self._count = 0
        self._skipped = 0

    def update(self, session):
        self._count += 1
        text = session["expectedText"]

        lookups = session["_lookups"]
        assert len(lookups) == 1
        lookup = lookups[0]

        latency_ms = session["latencies"]
        if latency_ms is None:
            self._skipped += 1
            return

        for kind, latency in latency_ms.items():
            self._kinds[kind] += 1
            self._latencies[kind] = self._latencies.get(kind, 0) + latency

    def print(self):
        tuples = []
        for kind, latency in self._latencies.items():
            avg_duration = latency / self._kinds[kind]
            tuples.append((avg_duration, latency, self._kinds[kind], kind))
        tuples.sort(reverse=True)

        print(f"duration by contributor kinds (skipped = {self._skipped}):")
        for avg, latency, cnt, kind in tuples:
            print(f"    {avg:.3f} ({latency} / {cnt}) - {kind}")


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


class ContiguousApproxRecall(Metric):
    def __init__(self, delay_ms):
        self._found = 0
        self._count = 0
        self._delay_ms = delay_ms
        self._skipped = 0

    def update(self, session):
        if not session["is_contiguous"]:
            self._skipped += 1
            return

        text = session["expectedText"]
        variants = get_variants(session, self._delay_ms)

        found = text in variants

        self._found += found
        self._count += 1

    def print(self):
        ratio = self._found / self._count if self._count else 0
        print(f"contiguous approx recall ({self._delay_ms}ms, skipped = {self._skipped}): {ratio:.3f} ({self._found} / {self._count})")


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


class TrickyBaselineMetric(Metric):
    def __init__(self):
        self._total_latency = 0
        self._count = 0
        self._skipped = 0
        self._found = 0

    def update(self, session):
        lookups = session["_lookups"]
        assert len(lookups) == 1

        latencies = session["latencies"]
        if latencies is None and session["is_contiguous"]:
            import ipdb; ipdb.set_trace(context=15)
        if latencies is None:
            self._skipped += 1
            return

        self._count += 1
        for kind in [
            ('KotlinCompletionContributor', 'kinds-descriptor-BasicCompletionSession.kt:859-LookupElementsCollector.kt:91'),
            ('KotlinCompletionContributor', 'kind-OverridesCompletion.kt:79'),
            ('KotlinCompletionContributor', 'kind-BasicCompletionSession.kt:711'),
            ('KotlinCompletionContributor', 'kind-BasicCompletionSession.kt:438'),
            ('KotlinCompletionContributor', 'kind-BasicCompletionSession.kt:445'),
        ]:
            if kind in latencies:
                self._total_latency += latencies[kind]
                text = session["expectedText"]
                self._found += text in [
                    suggestion["text"]
                    for suggestion in session["_lookups"][0]["suggestions"]
                    if kind == (suggestion["contributor"], suggestion["contributorKind"])
                ]
                break

    def print(self):
        print(f"tricky baseline metrics (skipped = {self._skipped}):")
        latency = self._total_latency / self._count if self._count else 0
        print(f"    latency: {latency:.3f} ({self._total_latency:.3f} / {self._count})")
        recall = self._found / self._count if self._count else 0
        print(f"    recall: {recall:.3f} ({self._found} / {self._count})")


def make_all_metrics():
    metrics = [
        Recall(),
        ContiguousRecall(),
        ApproxRecall(delay_ms=50),
        ContiguousApproxRecall(delay_ms=50),
        ApproxRecall(delay_ms=100),
        ContiguousApproxRecall(delay_ms=100),
        ApproxRecall(delay_ms=200),
        ContiguousApproxRecall(delay_ms=200),
        ApproxRecall(delay_ms=250),
        ContiguousApproxRecall(delay_ms=250),
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
        MeanApproxLatency(delay_ms=100),
        ContributorKindRecall(),
        ContributorKindDuration(),
        TrickyBaselineMetric(),
    ]
    return metrics


def process_file(path, agg_metrics=None):
    with open(path) as f:
        obj = json.load(f)

    metrics = make_all_metrics()

    print(obj["filePath"])

    for session in obj["sessions"]:
        assert len(session["_lookups"]) == 1
        session["latencies"] = utils.calc_latencies(session["_lookups"][0])
        session["is_contiguous"] = session["latencies"] is not None

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
