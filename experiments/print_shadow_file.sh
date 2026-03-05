#!/bin/bash

for server in node0 node1; do
    output=$(ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 "$server" "[ -d /opt/ShadowDirectory ] && tree /opt/ShadowDirectory" 2>/dev/null)
    if [ -n "$output" ]; then
        echo "$output"
    fi
done