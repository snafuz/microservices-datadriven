#!/bin/bash
## Copyright (c) 2021 Oracle and/or its affiliates.
## Licensed under the Universal Permissive License v 1.0 as shown at https://oss.oracle.com/licenses/upl/

SCRIPT_DIR=$(dirname "$0")


if [ -z "$DOCKER_REGISTRY" ]; then
    echo "DOCKER_REGISTRY not set. Will get it with state_get"
  export DOCKER_REGISTRY=$(state_get DOCKER_REGISTRY)
fi

if [ -z "$DOCKER_REGISTRY" ]; then
    echo "Error: DOCKER_REGISTRY env variable needs to be set!"
    exit 1
fi

if [ -z "$INVENTORY_PDB_NAME" ]; then
    echo "INVENTORY_PDB_NAME not set. Will get it with state_get"
  export INVENTORY_PDB_NAME=$(state_get PARTICIPANTDB_NAME)
fi

if [ -z "$INVENTORY_PDB_NAME" ]; then
    echo "Error: INVENTORY_PDB_NAME env variable needs to be set!"
    exit 1
fi

echo create travelagency-springboot deployment and service...

export CURRENTTIME=$( date '+%F_%H:%M:%S' )
echo CURRENTTIME is $CURRENTTIME  ...this will be appended to generated deployment yaml
cp travelagency-springboot-deployment.yaml travelagency-springboot-deployment-$CURRENTTIME.yaml

sed -e "s|%DOCKER_REGISTRY%|${DOCKER_REGISTRY}|g" travelagency-springboot-deployment-$CURRENTTIME.yaml > /tmp/travelagency-springboot-deployment-$CURRENTTIME.yaml
mv -- /tmp/travelagency-springboot-deployment-$CURRENTTIME.yaml travelagency-springboot-deployment-$CURRENTTIME.yaml
sed -e "s|%INVENTORY_PDB_NAME%|${INVENTORY_PDB_NAME}|g" travelagency-springboot-deployment-$CURRENTTIME.yaml > /tmp/travelagency-springboot-deployment-$CURRENTTIME.yaml
mv -- /tmp/travelagency-springboot-deployment-$CURRENTTIME.yaml travelagency-springboot-deployment-$CURRENTTIME.yaml
sed -e "s|%OCI_REGION%|${OCI_REGION}|g" travelagency-springboot-deployment-${CURRENTTIME}.yaml > /tmp/travelagency-springboot-deployment-$CURRENTTIME.yaml
mv -- /tmp/travelagency-springboot-deployment-$CURRENTTIME.yaml travelagency-springboot-deployment-$CURRENTTIME.yaml
sed -e "s|%VAULT_SECRET_OCID%|${VAULT_SECRET_OCID}|g" travelagency-springboot-deployment-${CURRENTTIME}.yaml > /tmp/travelagency-springboot-deployment-$CURRENTTIME.yaml
mv -- /tmp/travelagency-springboot-deployment-$CURRENTTIME.yaml travelagency-springboot-deployment-$CURRENTTIME.yaml

if [ -z "$1" ]; then
    kubectl apply -f "$SCRIPT_DIR"/travelagency-springboot-deployment-${CURRENTTIME}.yaml -n msdataworkshop
else
    kubectl apply -f <(istioctl kube-inject -f "$SCRIPT_DIR"/travelagency-springboot-deployment-${CURRENTTIME}.yaml) -n msdataworkshop
fi
