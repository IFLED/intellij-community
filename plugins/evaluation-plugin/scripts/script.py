import json
import sys


def get_variants(session, delay_ms=None):
    assert len(session["_lookups"]) == 1
    lookup = session["_lookups"][0]

    if delay_ms:
        latencies = [
            suggestion["lookupLatency"]
            for suggestion in lookup["suggestions"]
        ]

        if not latencies:
            return []

        min_latency = min(latencies)

        return [
            suggestion["text"]
            for suggestion in lookup["suggestions"]
            if suggestion["lookupLatency"] <= min_latency + delay_ms
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


class MeanOracleMinLatency(Metric):
    def __init__(self):
        self._total_latency = 0
        self._count = 0
        self._skipped = 0

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
            suggestion["lookupLatency"]
            for suggestion in lookup["suggestions"]
            if text == suggestion["text"]
        ]
        if latencies:
            return min(latencies)

    def print(self):
        ratio = self._total_latency / self._count if self._count else 0
        print(f"min oracle latency: {ratio:.3f} ({self._total_latency:.3f} / {self._count}, skpped = {self._skipped})")


class MeanOracleMaxLatency(Metric):
    def __init__(self):
        self._total_latency = 0
        self._count = 0
        self._skipped = 0

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
            suggestion["lookupLatency"]
            for suggestion in lookup["suggestions"]
            if text == suggestion["text"]
        ]
        if latencies:
            return max(latencies)

    def print(self):
        ratio = self._total_latency / self._count if self._count else 0
        print(f"max oracle latency: {ratio:.3f} ({self._total_latency:.3f} / {self._count}, skpped = {self._skipped})")


class MeanOracleMeanLatency(Metric):
    def __init__(self):
        self._total_latency = 0
        self._count = 0
        self._skipped = 0

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
            suggestion["lookupLatency"]
            for suggestion in lookup["suggestions"]
            if text == suggestion["text"]
        ]
        if latencies:
            return sum(latencies) / len(latencies)

    def print(self):
        ratio = self._total_latency / self._count if self._count else 0
        print(f"mean oracle latency: {ratio:.3f} ({self._total_latency:.3f} / {self._count}, skpped = {self._skipped})")
        

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
            suggestion["lookupLatency"]
            for suggestion in lookup["suggestions"]
        ]

        if not latencies:
            return

        min_latency = min(latencies)

        return min_latency + self._delay_ms

    def print(self):
        ratio = self._total_latency / self._count if self._count else 0
        print(f"mean approx latency ({self._delay_ms}ms): {ratio:.3f} ({self._total_latency:.3f} / {self._count}, skpped = {self._skipped})")


def process_file(path):
    with open(path) as f:
        obj = json.load(f)

    metrics = [
        Recall(),
        ApproxRecall(delay_ms=50),
        ApproxRecall(delay_ms=100),
        ApproxRecall(delay_ms=200),
        MeanPopupLatency(),
        MeanOracleMinLatency(),
        MeanOracleMeanLatency(),
        MeanOracleMaxLatency(),
        MeanApproxLatency(delay_ms=50),
        MeanApproxLatency(delay_ms=100),
        MeanApproxLatency(delay_ms=200),
    ]

    print(obj["filePath"])

    for session in obj["sessions"]:
        for metric in metrics:
            metric.update(session)
        # variants = get_variants(session)
        # text = session["expectedText"]

        # print(session["id"], text, session["_lookups"][0]["prefix"], len(variants), text in variants)

    for metric in metrics:
        print("  ", end="")
        metric.print()


def main():
    print(sys.argv, file=sys.stderr)
    path = sys.argv[1]
    process_file(path)


if __name__ == "__main__":
    main()
