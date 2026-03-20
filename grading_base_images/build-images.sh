#!/bin/bash

# Exit on error
set -e

echo "Building Java grading base image..."
docker build -t enbug-grading-java-base-image:latest ./java

echo "Building Spring grading base image..."
docker build -t enbug-grading-spring-base-image:latest ./spring

echo "Successfully built base images!"
docker images | grep enbug-grading
