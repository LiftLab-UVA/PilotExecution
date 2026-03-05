#!/bin/bash
set -e

NODES="node0 node1 node2"
USER=$(whoami)
GROUP=$(id -gn)

echo "=== Configuring passwordless SSH for user $USER (group: $GROUP) across the cluster ==="

# 1. Generate key pair (if not exists)
if [ ! -f ~/.ssh/id_rsa ]; then
    echo "Generating SSH key..."
    ssh-keygen -t rsa -b 2048 -f ~/.ssh/id_rsa -N ""
fi

# 2. Configure local SSH config
cat > ~/.ssh/config << 'EOF'
Host *
    StrictHostKeyChecking no
    UserKnownHostsFile=/dev/null
    LogLevel ERROR
EOF
chmod 600 ~/.ssh/config

# 3. Get public key
PUBKEY=$(cat ~/.ssh/id_rsa.pub)

# 4. Configure authorized_keys for current user on all nodes via root
for node in $NODES; do
    echo "Configuring $node ..."
    sudo ssh -o StrictHostKeyChecking=no $node bash -c "'
        mkdir -p /users/$USER/.ssh
        chmod 700 /users/$USER/.ssh

        # Add public key (avoid duplicates)
        grep -qxF \"$PUBKEY\" /users/$USER/.ssh/authorized_keys 2>/dev/null || echo \"$PUBKEY\" >> /users/$USER/.ssh/authorized_keys

        chmod 600 /users/$USER/.ssh/authorized_keys
        chown -R $USER:$GROUP /users/$USER/.ssh
    '"
done

# 5. Copy private key and config to other nodes
for node in $NODES; do
    if [ "$node" != "$(hostname -s)" ]; then
        echo "Syncing keys to $node ..."
        sudo scp -o StrictHostKeyChecking=no ~/.ssh/id_rsa $node:/users/$USER/.ssh/id_rsa
        sudo scp -o StrictHostKeyChecking=no ~/.ssh/id_rsa.pub $node:/users/$USER/.ssh/id_rsa.pub
        sudo scp -o StrictHostKeyChecking=no ~/.ssh/config $node:/users/$USER/.ssh/config
        sudo ssh -o StrictHostKeyChecking=no $node "chown $USER:$GROUP /users/$USER/.ssh/id_rsa* /users/$USER/.ssh/config && chmod 600 /users/$USER/.ssh/id_rsa /users/$USER/.ssh/config"
    fi
done

# 6. Set /opt permissions on all nodes
echo ""
echo "=== Setting /opt directory permissions ==="
for node in $NODES; do
    echo "Setting permissions on $node:/opt ..."
    sudo ssh -o StrictHostKeyChecking=no $node "chmod -R 777 /opt"
done

echo ""
echo "=== Testing connections ==="
for node in $NODES; do
    echo -n "  $(hostname -s) -> $node: "
    ssh $node hostname
done

echo ""
echo "Done!"