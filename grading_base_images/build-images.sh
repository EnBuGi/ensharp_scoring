#!/bin/bash

# Exit on error
set -e

echo "Building Java grading base image..."
docker build -t huri0906/enbug-grading-java-base-image:latest ./java

echo "Building Spring grading base image..."
docker build -t huri0906/enbug-grading-spring-base-image:latest ./spring

echo "Successfully built base images!"
echo "Next step: docker login && docker push huri0906/enbug-grading-java-base-image:latest && docker push huri0906/enbug-grading-spring-base-image:latest"
docker images | grep huri0906/enbug-grading
