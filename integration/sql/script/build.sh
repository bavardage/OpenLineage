#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0.

set -e
/opt/python/cp37-cp37m/bin/python -m venv .env
source .env/bin/activate
python -m pip install maturin
curl https://sh.rustup.rs -sSf | sh -s -- -y
export CARGO_INCREMENTAL=0
source $HOME/.cargo/env && cargo test --no-default-features && maturin build --release