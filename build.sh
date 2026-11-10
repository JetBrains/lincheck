#!/usr/bin/env bash
nix build && docker load < result && docker run  jcstress-arm:latest