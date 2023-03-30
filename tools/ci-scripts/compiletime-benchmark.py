#!/usr/bin/env python3
#
# Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
# SPDX-License-Identifier: Apache-2.0
#

# converts time's stdout to markdown
import polars as pl
import subprocess

fp=open("./compile-all.sh", "w")
fp.write("""
git clone https://github.com/awslabs/smithy-rs.git
cd smithy-rs

./gradlew :aws:sdk:cargoCheck1
WORKDIR=pwd
PATH_TO_GENERATED_SDK="$WORKDIR/aws/sdk/build/aws-sdk/sdk"
export RUSTFLAGS="--cfg aws-sdk-unstable"

for i in $(ls $PATH_TO_GENERATED_SDK); do
    cd $PATH_TO_GENERATED_SDK/$i
    if [[ ! $($PATH_TO_GENERATED_SDK == *"aws-"*) ]]; then
        # not-optimized
        echo $i >> unoptimized.txt
        time cargo build >> unoptimized.txt
        echo "=======================================" >> compiletime.txt

        # optimized
        echo "sdk $i" >> optimized.txt
        time cargo build --release >> optimized.txt
        echo "=======================================" >> compiletime.txt
    fi
done
""")
fp.flush()
fp.close()

subprocess.call(["bash", "./compile-all.sh"])

DELIMITER="======================================="
def main(file: str):
    fp = open(file, "r")
    contents = fp.read()

    stack = []
    for i in contents.split(DELIMITER):
        hashmap = {}
        for line in i.splitlines():
            [key, val] = list(filter(lambda x: x != "", line.split(" ")))
            hashmap[key] = val

        stack.append(hashmap)

    df = pl.DataFrame(stack)
    # converts it to markdown file
    df.to_pandas().to_markdown(file.replace(".txt", ".md"))


main("unoptimized.txt")
main("optimized.txt")