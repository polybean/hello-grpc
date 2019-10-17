#!/bin/bash

PROJECT_HOME=$(realpath "$(dirname -- $0)/..")
JAVA_SRC_BASE=$PROJECT_HOME/java/src/main/java

protoc \
  --proto_path $PROJECT_HOME/protos \
  --go_out=plugins=grpc:$GOPATH/src/ \
  $PROJECT_HOME/protos/greeting.proto

# https://repo1.maven.org/maven2/io/grpc/protoc-gen-grpc-java/1.24.0/
protoc \
  --proto_path $PROJECT_HOME/protos \
  --java_out=$JAVA_SRC_BASE \
  --grpc_out=$JAVA_SRC_BASE \
  --plugin=protoc-gen-grpc=$HOME/bin/protoc-gen-grpc-java-1.24.0-osx-x86_64.exe \
  $PROJECT_HOME/protos/greeting.proto
