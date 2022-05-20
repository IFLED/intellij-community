import sys

import utils


def main():
    print(sys.argv, file=sys.stderr)
    paths = sys.argv[1:]

    # id2feats = utils.read_id2feats(paths)
    # print(len(id2feats))
    # for _, pair in zip(range(2), id2feats.items()):
    #     print(pair, file=sys.stderr)

    id2session, id2fname = utils.read_sessions(paths)
    print(len(id2session))


if __name__ == "__main__":
    main()
