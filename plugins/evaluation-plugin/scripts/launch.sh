#!/usr/bin/env bash

set -euo pipefail
set -o noclobber

json=/Users/ifled/projects/csc/ide_perf_practice/detekt/completion-evaluation/2022-04-25_16-35-19/data/files/jsons/"AstPrinter.kt(0).json.gz.json"
# python3 script.py "$json"

json=/Users/ifled/projects/csc/ide_perf_practice/detekt/completion-evaluation/2022-04-25_16-35-19/data/files/jsons/"ElementPrinter.kt(2).json.gz.json"
# python3 script.py "$json"

# for json in /Users/ifled/projects/csc/ide_perf_practice/detekt/completion-evaluation/2022-04-25_16-35-19/data/files/jsons/*; do
#     python3 script.py "$json"
# done

# for json in /Users/ifled/projects/csc/ide_perf_practice/detekt/completion-evaluation/2022-05-02_16-55-43/data/files/jsons/*.json; do
#     python3 script.py "$json"
# done

dir=/Users/ifled/projects/csc/ide_perf_practice/detekt/completion-evaluation/2022-05-02_16-55-43/data/files/jsons
python3 script.py "$dir"
