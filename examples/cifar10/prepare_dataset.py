"""Convert the checksum-verified CIFAR-10 binary training split to immutable MDS."""

import argparse
import hashlib
import json
import tarfile
from pathlib import Path

SOURCE = "https://www.cs.toronto.edu/~kriz/cifar-10-binary.tar.gz"
SOURCE_MD5 = "c32a1d4ab5d03f1284b67883e8d87530"
SHARD_BYTES = 96 * 1024 * 1024
RECORD_BYTES = 3073


def records(archive):
    """Read only the five named training members, without extracting filesystem paths."""
    with tarfile.open(archive, "r:gz") as source:
        ordinal = 0
        for batch in range(1, 6):
            member = source.getmember(f"cifar-10-batches-bin/data_batch_{batch}.bin")
            if not member.isfile() or member.size != 10_000 * RECORD_BYTES:
                raise ValueError("Unexpected CIFAR-10 training member shape")
            with source.extractfile(member) as stream:
                for _ in range(10_000):
                    record = stream.read(RECORD_BYTES)
                    if len(record) != RECORD_BYTES or record[0] >= 10:
                        raise ValueError("Invalid CIFAR-10 training record")
                    yield {
                        "image": record[1:],
                        "label": record[0],
                        "source_index": ordinal,
                    }
                    ordinal += 1


def prepare(archive, destination):
    with archive.open("rb") as source:
        if hashlib.file_digest(source, "md5").hexdigest() != SOURCE_MD5:
            raise ValueError(
                "Archive differs from the original CIFAR-10 download checksum"
            )
    if destination.exists():
        raise ValueError(
            "Use a new output directory; published corpora must not be overwritten"
        )
    from streaming import MDSWriter

    count = 0
    with MDSWriter(
        out=str(destination),
        columns={"image": "bytes", "label": "int", "source_index": "int"},
        compression=None,
        hashes=["sha256"],
        size_limit=SHARD_BYTES,
    ) as writer:
        for record in records(archive):
            writer.write(record)
            count += 1
    index = json.loads((destination / "index.json").read_text())
    largest = max(shard["raw_data"]["bytes"] for shard in index["shards"])
    if count != 50_000 or largest <= 64 * 1024 * 1024:
        raise ValueError(
            "Qualification requires all training images and a shard above 64 MiB"
        )
    with archive.open("rb") as source:
        digest = hashlib.file_digest(source, "sha256").hexdigest()
    return {
        "source": SOURCE,
        "sourceMd5": SOURCE_MD5,
        "sourceSha256": digest,
        "trainingImages": count,
        "largestShardBytes": largest,
        "shardLimitBytes": SHARD_BYTES,
    }


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    print(json.dumps(prepare(args.archive, args.destination), indent=2))
