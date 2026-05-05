#!/bin/bash

# Exit on error
set -e

echo "Building Java grading base image (v4)..."
docker build -t huri0906/enbug-grading-java-base-image:v4 ./java

echo "Building Spring grading base image (v4)..."
docker build -t huri0906/enbug-grading-spring-base-image:v4 ./spring

echo "Successfully built base images (v4)!"
echo "Pushing images to Docker Hub..."
docker push huri0906/enbug-grading-java-base-image:v4
docker push huri0906/enbug-grading-spring-base-image:v4

docker images | grep huri0906/enbug-grading
